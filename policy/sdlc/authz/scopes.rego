# The default policy (#30, #95 FR-2): what the token must hold for each action. This is the scope
# model of docs/AUTH.md (#116), unchanged: graph:read to read, graph:write to change the graph, and
# graph:admin as well to administer it as a whole. The backend's ScopePolicy names the same scopes
# per route family, and DefaultPolicyParityTest holds the two to one answer.
package sdlc.authz

import rego.v1

held := {s | some s in object.get(input.subject, "scopes", [])}

scope_requirement := {s | some s in data.sdlc.config.action_scopes[input.action]}

missing := sort([s | some s in scope_requirement; not s in held])

# An instance with no identity provider answers reads for anyone (docs/AUTH.md, #118): there is no
# token, so there are no scopes to compare. Its writes are refused before they get here.
anonymous_read if {
	input.subject.kind == "anonymous"
	object.get(input, ["context", "mode"], "") == "anonymous-read-only"
	input.action in reads
}

scope_denial := {
	"policy": "scopes",
	"reason": concat("", [input.action, " needs ", concat(", ", sort(scope_requirement)), "; the token holds ", held_text]),
} if {
	not anonymous_read
	count(missing) > 0
}

held_text := concat(", ", sort(held)) if {
	count(held) > 0
} else := "no graph scope"
