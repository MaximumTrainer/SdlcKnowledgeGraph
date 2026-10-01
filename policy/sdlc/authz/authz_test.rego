# The default policy (#30, #95 FR-2) grants exactly what the scopes of docs/AUTH.md grant: one
# action at a time, for a user, a service and an anonymous reader. DefaultPolicyParityTest in the
# backend checks the same against every route ScopePolicy declares.
package sdlc.authz_test

import data.sdlc.authz
import rego.v1

user(scopes) := {"id": "dan", "kind": "user", "scopes": scopes, "teams": []}

service(scopes) := {"id": "github-connector", "kind": "service", "scopes": scopes, "teams": ["platform"]}

node(type) := {"kind": "node", "type": type}

decide(subject, action, resource) := d if {
	d := authz.decision with input as {
		"subject": subject,
		"action": action,
		"resource": resource,
		"context": {"mode": "oidc"},
	}
}

test_read_needs_graph_read if {
	decide(user(["graph:read"]), "read", node("Repository")).allow
	not decide(user([]), "read", node("Repository")).allow
	not decide(user(["graph:write"]), "read", node("Repository")).allow
}

test_a_query_sent_as_a_post_needs_graph_read_only if {
	decide(user(["graph:read"]), "query", {"kind": "endpoint", "filtered": false}).allow
}

test_every_write_needs_graph_write if {
	every action in ["create", "update", "delete", "link", "sync"] {
		decide(user(["graph:read", "graph:write"]), action, node("Team")).allow
		not decide(user(["graph:read"]), action, node("Team")).allow
	}
}

test_administering_the_graph_needs_graph_admin_and_graph_write if {
	decide(user(["graph:write", "graph:admin"]), "admin", {"kind": "endpoint"}).allow
	not decide(user(["graph:write"]), "admin", {"kind": "endpoint"}).allow
	not decide(user(["graph:admin"]), "admin", {"kind": "endpoint"}).allow
}

test_a_scope_refusal_says_what_was_needed_and_what_was_held if {
	d := decide(user(["graph:read"]), "create", node("Team"))
	not d.allow
	d.policy == "scopes"
	d.required == ["graph:write"]
	d.reason == "create needs graph:write; the token holds graph:read"
}

test_a_token_with_no_graph_scope_is_told_so if {
	d := decide(user([]), "read", node("Team"))
	d.reason == "read needs graph:read; the token holds no graph scope"
}

test_a_service_is_judged_by_its_scopes_as_a_user_is if {
	every action in ["read", "query", "create", "update", "delete", "link", "sync", "admin"] {
		every scopes in [[], ["graph:read"], ["graph:read", "graph:write"], ["graph:admin", "graph:read", "graph:write"]] {
			decide(service(scopes), action, node("Team")).allow == decide(user(scopes), action, node("Team")).allow
		}
	}
}

test_by_default_a_service_may_delete if {
	decide(service(["graph:write"]), "delete", node("Team")).allow
}

test_a_service_may_be_refused_deletes_by_configuration if {
	d := authz.decision with input as {"subject": service(["graph:write"]), "action": "delete", "resource": node("Team")}
		with data.sdlc.config.service_may_delete as false
	not d.allow
	d.policy == "services.delete"
}

test_an_anonymous_reader_of_a_read_only_instance_reads_without_a_token if {
	anonymous := {"id": "anonymous", "kind": "anonymous", "scopes": []}
	d := authz.decision with input as {
		"subject": anonymous, "action": "read", "resource": node("Repository"),
		"context": {"mode": "anonymous-read-only"},
	}
	d.allow
	not decide(anonymous, "create", node("Repository")).allow
}

test_an_unknown_action_is_refused if {
	d := decide(user(["graph:admin", "graph:read", "graph:write"]), "teleport", node("Team"))
	not d.allow
	d.policy == "actions"
}

test_by_default_nothing_is_redacted_or_hidden if {
	d := decide(user(["graph:read"]), "read", node("Team"))
	d.allow
	d.redact == []
	d.clearance == "restricted"
	f := authz.filter with input as {
		"subject": user(["graph:read"]),
		"action": "read",
		"resources": [{"id": "Team:platform", "type": "Team"}, {"id": "ServicePrincipal:bot", "type": "ServicePrincipal"}],
	}
	f.denied == []
	f.allowed == {"Team:platform": [], "ServicePrincipal:bot": []}
}

test_by_default_an_endpoint_that_cannot_filter_still_answers if {
	decide(user(["graph:read"]), "read", {"kind": "endpoint", "filtered": false}).allow
}
