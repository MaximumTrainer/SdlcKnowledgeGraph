# The agent-actions policy (#95 FR-4, FR-5): rollback on graph facts.
package sdlc.agent_actions_test

import data.sdlc.agent_actions
import rego.v1

deployment(overrides) := object.union(
	{
		"id": "Deployment:payments#production#42", "found": true, "kind": "node", "type": "Deployment",
		"confidence": 1.0, "inferred": false, "ageMinutes": 20, "priorArtifact": true, "environment": "production",
	},
	overrides,
)

evaluate(action, facts) := d if {
	d := agent_actions.decision with input as {
		"action": action,
		"subject": {"id": "remediation-agent", "kind": "agent"},
		"facts": facts,
	}
}

test_rollback_is_allowed_on_a_fresh_reported_deployment if {
	d := evaluate("rollback", [deployment({})])
	d.allow
	d.reasons == ["the deployment Deployment:payments#production#42 was reported at confidence 1.0, 20 minutes ago, and an earlier artifact went to production"]
}

test_rollback_is_refused_on_an_inferred_cause if {
	edge := {"id": "DEPENDS_ON:Repository:a>Repository:b", "found": true, "kind": "edge", "type": "DEPENDS_ON", "confidence": 0.6, "inferred": true}
	d := evaluate("rollback", [edge])
	not d.allow
	some reason in d.reasons
	startswith(reason, "inferred cause: DEPENDS_ON:Repository:a>Repository:b was inferred by a rule at confidence 0.6")
}

test_rollback_is_refused_on_a_stale_deployment if {
	d := evaluate("rollback", [deployment({"ageMinutes": 61})])
	not d.allow
	d.reasons == ["the deployment Deployment:payments#production#42 was observed 61 minutes ago, more than 60"]
}

test_rollback_at_exactly_the_limit_is_allowed if {
	evaluate("rollback", [deployment({"ageMinutes": 60})]).allow
}

test_rollback_is_refused_below_full_confidence if {
	not evaluate("rollback", [deployment({"confidence": 0.9})]).allow
}

test_rollback_is_refused_on_an_inferred_deployment if {
	d := evaluate("rollback", [deployment({"inferred": true, "confidence": 0.8})])
	not d.allow
}

test_rollback_is_refused_with_nothing_to_roll_back_to if {
	d := evaluate("rollback", [deployment({"priorArtifact": false})])
	d.reasons == ["no earlier artifact was deployed to production to roll back to"]
}

test_a_fact_the_graph_does_not_hold_counts_for_nothing if {
	d := evaluate("rollback", [{"id": "Deployment:made-up", "found": false}])
	not d.allow
	d.reasons == ["fact Deployment:made-up is not in the graph", "rollback needs the causal Deployment among the facts"]
}

test_an_action_no_policy_decides_is_refused if {
	d := evaluate("delete-production", [deployment({})])
	not d.allow
	d.reasons == ["no agent-actions policy decides 'delete-production'"]
}
