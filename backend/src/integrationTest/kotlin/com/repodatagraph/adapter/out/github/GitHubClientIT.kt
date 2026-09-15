package com.repodatagraph.adapter.out.github

import com.repodatagraph.support.connector.FakeGitHub
import com.repodatagraph.support.connector.FakeRepo
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The client, against a GitHub that is not GitHub but speaks HTTP.
 *
 * Everything asserted here is something a mocked client could not get wrong and so could not prove:
 * that the `Link` header is followed to the last page, that a rate limit is recognised rather than
 * hammered through, and that a 500 is retried while a 404 is an answer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubClientIT {
    private val github = FakeGitHub()
    private lateinit var client: GitHubClient

    @BeforeAll
    fun startFake() {
        github.start()
    }

    @AfterAll
    fun stopFake() {
        github.stop()
    }

    @BeforeEach
    fun reset() {
        github.reset()
        val properties =
            GitHubProperties(orgs = listOf(ORG), baseUrl = github.baseUrl, token = "fake-token", waitForResetSeconds = 5)
        client = GitHubClient(GitHubHttp(properties, RestClient.builder()), properties)
    }

    @Test
    fun `reads every page, not just the first`() {
        val all = (1..PAGED_TOTAL).map { FakeRepo(name = "repo-$it") }
        github.hasRepositories(ORG, all.take(PAGE_SIZE), all.drop(PAGE_SIZE))

        val read = client.repositories(ORG).toList()

        // A connector that reads only the first page is the classic way to silently ingest a third of
        // an estate: nothing fails, the graph is just quietly incomplete.
        assertThat(read).hasSize(PAGED_TOTAL)
        assertThat(read.map { it.name }).contains("repo-1", "repo-$PAGED_TOTAL")
    }

    @Test
    fun `stops when there is no next page`() {
        github.hasRepositories(ORG, listOf(FakeRepo(name = "only")))

        assertThat(client.repositories(ORG).toList()).hasSize(1)
    }

    @Test
    fun `reads the fields the mapper needs`() {
        github.hasRepositories(
            ORG,
            listOf(FakeRepo(name = "Payments", topics = listOf("java"), language = "Kotlin", archived = true)),
        )

        val repo = client.repositories(ORG).single()

        assertThat(repo.name).isEqualTo("Payments")
        assertThat(repo.fullName).isEqualTo("acme/Payments")
        assertThat(repo.htmlUrl).isEqualTo("https://github.com/acme/Payments")
        assertThat(repo.topics).containsExactly("java")
        assertThat(repo.language).isEqualTo("Kotlin")
        assertThat(repo.archived).isTrue()
        assertThat(repo.pushedAt).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"))
    }

    @Test
    fun `identifies itself and asks for a pinned API version`() {
        github.hasRepositories(ORG, listOf(FakeRepo(name = "only")))

        client.repositories(ORG).toList()

        val request = github.lastRequest()
        // A pinned version, because GitHub changes response shapes behind an unpinned one and the
        // first sign would be a mapper failing on a field that used to be there.
        assertThat(request.getHeader("X-GitHub-Api-Version")).isNotBlank()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer fake-token")
        assertThat(request.getHeader("Accept")).contains("application/vnd.github+json")
    }

    @Test
    fun `asks for a full page at a time`() {
        github.hasRepositories(ORG, listOf(FakeRepo(name = "only")))

        client.repositories(ORG).toList()

        // 100 is GitHub's maximum. Anything smaller multiplies requests against a rate limit shared
        // with everything else the token is used for.
        assertThat(github.lastRequest().queryParameter("per_page").firstValue()).isEqualTo("100")
    }

    @Test
    fun `gives up on a rate limit it would have to wait out`() {
        val resetsAt = Instant.now().plus(Duration.ofHours(1))
        github.hasExhaustedRateLimit(ORG, resetsAt)

        // Syncs run on a pool of four threads. A run that sleeps for an hour stops every other
        // connector as surely as a deadlock would, so a long reset ends the run instead.
        assertThatThrownBy { client.repositories(ORG).toList() }
            .isInstanceOf(GitHubRateLimitException::class.java)
            // Naming the reset makes "why did the sync stop" answerable from the run record alone,
            // rather than by going and asking GitHub.
            .hasMessageContaining(resetsAt.truncatedTo(ChronoUnit.SECONDS).toString())
    }

    @Test
    fun `waits out a rate limit that is about to reset`() {
        github.hasExhaustedRateLimit(
            ORG,
            resetsAt = Instant.now().plusSeconds(1),
            thenReturning = listOf(FakeRepo(name = "patient")),
        )

        // A rate limit is an instruction rather than an error. A reset seconds away is worth waiting
        // for; failing the run would throw away everything the next attempt would have read anyway.
        assertThat(client.repositories(ORG).toList()).hasSize(1)
    }

    @Test
    fun `retries a server error and carries on`() {
        github.failsThenReturns(ORG, times = 2, repos = listOf(FakeRepo(name = "flaky")))

        // A 5xx from GitHub is usually a moment rather than a condition, and failing the whole run on
        // one would make a sync of a large org close to impossible.
        assertThat(client.repositories(ORG).toList()).hasSize(1)
    }

    @Test
    fun `gives up on a server error that will not stop`() {
        github.failsThenReturns(ORG, times = FakeGitHub.ALWAYS)

        assertThatThrownBy { client.repositories(ORG).toList() }
            .isInstanceOf(GitHubUnavailableException::class.java)
            .hasMessageContaining("500")
    }

    @Test
    fun `reads CODEOWNERS wherever GitHub allows it to live`() {
        github.files.hasCodeowners(ORG, "payments", "* @acme/platform-team", path = ".github/CODEOWNERS")

        val owners = client.codeowners(ORG, "payments")

        assertThat(owners?.teams?.map { it.slug }).containsExactly("acme/platform-team")
    }

    @Test
    fun `treats a missing CODEOWNERS as an absence, not a failure`() {
        github.files.hasCodeowners(ORG, "payments", content = null)

        // 404 is GitHub's normal answer for a repository that has no CODEOWNERS, which is most of
        // them. Treating it as an error would fail nearly every run.
        assertThat(client.codeowners(ORG, "payments")).isNull()
    }

    @Test
    fun `says whether it can reach GitHub at all`() {
        assertThat(client.isReachable()).isTrue()
    }

    private companion object {
        const val ORG = "acme"
        const val PAGE_SIZE = 100
        const val PAGED_TOTAL = 150
    }
}
