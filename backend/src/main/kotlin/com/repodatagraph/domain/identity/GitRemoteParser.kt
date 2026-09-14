package com.repodatagraph.domain.identity

import com.repodatagraph.domain.exception.InvalidGitRemoteException
import org.springframework.stereotype.Component

/** Parses any written form of a git remote into the parts that identify it. */
@Component
class GitRemoteParser {
    fun parse(input: String): GitRemote = throw InvalidGitRemoteException("not implemented yet", input)
}
