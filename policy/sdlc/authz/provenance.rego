# Who may state a fact as a system of record (#117, #95 FR-3). A write through the API is recorded at
# confidence 1.0 and not inferred, as the system of record's own report. Naming any source but
# `manual` claims that, so it needs that source's own scope, graph:write:<source>: a connector speaks
# for its own system, and a person, who holds no source scope, can state only their own word.
package sdlc.authz

import rego.v1

stated := object.get(input.resource, "provenance", null)

claims_system_of_record if {
	is_object(stated)
	stated.sourceSystem != "manual"
	stated.confidence == 1
	stated.inferred == false
}

provenance_requirement := {"graph:write", concat("", ["graph:write:", stated.sourceSystem])} if {
	input.action in {"create", "update", "link"}
	claims_system_of_record
} else := set()

provenance_missing := [s | some s in provenance_requirement; not s in held]

provenance_denial := {
	"policy": "provenance.confidence",
	"reason": concat("", [
		"stating a fact as ", stated.sourceSystem,
		" at confidence 1.0 claims to be its system of record, which needs graph:write:", stated.sourceSystem,
	]),
} if {
	count(provenance_missing) > 0
}
