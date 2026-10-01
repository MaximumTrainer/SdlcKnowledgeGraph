# Who may state a fact as a system of record (#117, #95 FR-3).
package sdlc.authz_test

import data.sdlc.authz
import rego.v1

stating(scopes, source) := d if {
	d := authz.decision with input as {
		"subject": {"id": "dan", "kind": "user", "scopes": scopes, "teams": []},
		"action": "create",
		"resource": {
			"kind": "node", "type": "CloudResource",
			"provenance": {"sourceSystem": source, "confidence": 1.0, "inferred": false},
		},
	}
}

test_a_manual_user_may_not_claim_system_of_record_confidence if {
	d := stating(["graph:read", "graph:write"], "aws")
	not d.allow
	d.policy == "provenance.confidence"
	d.required == ["graph:write", "graph:write:aws"]
}

test_a_connector_holding_the_source_scope_may if {
	stating(["graph:read", "graph:write", "graph:write:aws"], "aws").allow
}

test_a_manual_fact_needs_only_graph_write if {
	d := stating(["graph:write"], "manual")
	d.allow
	d.required == ["graph:write"]
}

test_a_source_scope_never_stands_in_for_graph_write if {
	d := stating(["graph:write:aws"], "aws")
	not d.allow
	d.policy == "scopes"
}
