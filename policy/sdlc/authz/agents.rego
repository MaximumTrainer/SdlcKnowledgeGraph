# What an agent may never do (#30 FR3). An agent is judged by exactly the rules a user with the same
# roles and scopes is; this is the one rule that is about agents alone. Administering the graph as a
# whole - applying ontology migrations, archiving, merging nodes - is a person's decision.
package sdlc.authz

import rego.v1

agent_denial := {"policy": "agents", "reason": "agents may not perform admin actions"} if {
	input.subject.kind == "agent"
	input.action == "admin"
}

# #95 FR-2 asks that a service principal may not delete. Off by default, because connectors delete
# what their source no longer reports today (docs/adr/0020); data.sdlc.config.service_may_delete
# turns it on.
service_denial := {"policy": "services.delete", "reason": "a service or agent may not delete"} if {
	input.subject.kind in {"service", "agent"}
	input.action == "delete"
	data.sdlc.config.service_may_delete == false
}
