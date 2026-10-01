# The one rule about agents alone (#30 FR3): an agent never administers the graph, whatever its role
# or scopes, while a user with the same role and scopes may.
package sdlc.authz_test

import data.sdlc.authz
import rego.v1

test_an_agent_may_never_perform_an_admin_action if {
	superbot := {"id": "superbot", "kind": "agent", "scopes": all_scopes, "roles": ["admin"], "teams": []}
	d := decide_as(superbot, "admin", {"kind": "endpoint"})
	not d.allow
	d.policy == "agents"
	d.reason == "agents may not perform admin actions"
}

test_an_agent_without_roles_may_not_administer_either if {
	bot := {"id": "bot", "kind": "agent", "scopes": all_scopes, "teams": []}
	not decide_as(bot, "admin", {"kind": "endpoint"}).allow
}

test_a_user_with_the_same_role_may if {
	dan := {"id": "dan", "kind": "user", "scopes": all_scopes, "roles": ["admin"], "teams": []}
	decide_as(dan, "admin", {"kind": "endpoint"}).allow
}

test_a_service_that_is_not_an_agent_may if {
	connector := {"id": "c", "kind": "service", "scopes": all_scopes, "teams": []}
	decide_as(connector, "admin", {"kind": "endpoint"}).allow
}
