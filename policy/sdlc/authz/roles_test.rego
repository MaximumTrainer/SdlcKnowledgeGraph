# Roles (#30 FR3, FR7, FR9): each role's actions, an agent judged as a user with the same roles,
# the ownership grant, and an unknown role refused.
package sdlc.authz_test

import data.sdlc.authz
import rego.v1

all_scopes := ["graph:admin", "graph:read", "graph:write"]

with_roles(kind, roles, teams) := {"id": "s", "kind": kind, "scopes": all_scopes, "roles": roles, "teams": teams}

decide_as(subject, action, resource) := d if {
	d := authz.decision with input as {
		"subject": subject,
		"action": action,
		"resource": resource,
	}
}

repository := {"kind": "node", "type": "Repository", "key": "github.com/acme/payments", "ownerTeams": ["github.com/acme/platform-team"]}

# Every role against every action, on an internal type.
expected := {
	"viewer": {"read", "query"},
	"curator": {"read", "query", "create", "update", "link"},
	"operator": {"read", "query", "create", "update", "link", "sync"},
	"admin": {"read", "query", "create", "update", "delete", "link", "sync", "admin"},
	"agent-reader": {"read", "query"},
	"agent-curator": {"read", "query", "link"},
}

test_each_role_permits_exactly_its_actions if {
	every role, permitted in expected {
		every action in authz.actions {
			permitted_here := action in permitted
			decide_as(with_roles("user", [role], []), action, {"kind": "node", "type": "Repository"}).allow == permitted_here
		}
	}
}

test_an_agent_with_a_role_is_judged_as_a_user_with_it if {
	every role, _ in expected {
		every action in authz.actions - {"admin"} {
			user := decide_as(with_roles("user", [role], []), action, repository)
			agent := decide_as(with_roles("agent", [role], []), action, repository)
			user.allow == agent.allow
			user.redact == agent.redact
		}
	}
}

test_a_role_refusal_names_the_role_and_the_action if {
	d := decide_as(with_roles("user", ["viewer"], []), "update", {"kind": "node", "type": "Repository"})
	not d.allow
	d.policy == "roles"
	d.reason == "the role viewer may not update Repository"
}

test_an_unknown_role_is_refused if {
	d := decide_as(with_roles("user", ["superuser"], []), "read", repository)
	not d.allow
	d.policy == "roles"
	d.reason == "none of the subject's roles is one the policy knows"
}

test_an_empty_roles_claim_is_refused if {
	not decide_as(with_roles("user", [], []), "read", repository).allow
}

test_unknown_roles_beside_a_known_one_do_not_matter if {
	decide_as(with_roles("user", ["viewer", "offline_access"], []), "read", repository).allow
}

test_a_member_of_an_owning_team_curates_what_it_owns if {
	bob := with_roles("user", ["viewer"], ["github.com/acme/platform-team"])
	d := decide_as(bob, "update", repository)
	d.allow
	d.reason == "a member of a team that owns it may act on it as a curator"
}

test_ownership_grants_nothing_on_what_the_team_does_not_own if {
	bob := with_roles("user", ["viewer"], ["github.com/acme/platform-team"])
	billing := {"kind": "node", "type": "Repository", "key": "github.com/acme/billing", "ownerTeams": ["github.com/acme/billing-team"]}
	not decide_as(bob, "update", billing).allow
}

test_ownership_grants_only_the_curator_actions if {
	bob := with_roles("user", ["viewer"], ["github.com/acme/platform-team"])
	not decide_as(bob, "delete", repository).allow
}

test_a_role_never_widens_the_scopes_on_the_token if {
	reader := {"id": "s", "kind": "user", "scopes": ["graph:read"], "roles": ["admin"], "teams": []}
	d := decide_as(reader, "create", repository)
	not d.allow
	d.policy == "scopes"
}
