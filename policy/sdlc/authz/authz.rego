# The one authorisation decision of the SDLC Knowledge Graph (#30, #95, docs/adr/0020).
#
# Every request under /api/v1 and /graphql is put to `decision` as a subject, an action and a
# resource; every list of nodes a read returns is put to `filter`. The backend evaluates this bundle
# in-process, compiled to WebAssembly, so the same Rego decides on every instance, with or without an
# Open Policy Agent server beside it.
#
# The rules are split by concern, all in this package:
#
# - scopes.rego       what the token must hold for the action (the default policy: today's scopes)
# - provenance.rego   who may state a fact as a system of record (#117, #95 FR-3)
# - agents.rego       what an agent may never do, whatever its role
# - roles.rego        what a role, or owning the resource, permits (only for a subject that has roles)
# - sensitivity.rego  how sensitive a type or property is, and how far the subject is cleared
#
# A request is refused by the first rule, in that order, that refuses it, so the refusal names one
# policy and one reason. The order puts scopes first because their refusal is the 403 the API has
# always given (docs/AUTH.md), and every caller already handles it.
package sdlc.authz

import rego.v1

# The actions the policy decides. Anything else is refused.
actions := {"read", "query", "create", "update", "delete", "link", "sync", "admin"}

reads := {"read", "query"}

# What the action needs of the token, and of the source a write states (provenance.rego).
required := sort(scope_requirement | provenance_requirement)

# The refusal, when there is one: the first that applies, in the order the header explains.
denial := d if {
	not input.action in actions
	d := {"policy": "actions", "reason": concat("", ["no policy decides the action '", format_action, "'"])}
} else := d if {
	d := scope_denial
} else := d if {
	d := provenance_denial
} else := d if {
	d := agent_denial
} else := d if {
	d := service_denial
} else := d if {
	d := role_denial
} else := d if {
	d := sensitivity_denial
}

format_action := a if {
	is_string(input.action)
	a := input.action
} else := "(none)"

decision := {
	"allow": false,
	"policy": denial.policy,
	"reason": denial.reason,
	"required": required,
	"redact": [],
	"clearance": clearance,
}

decision := {
	"allow": true,
	"policy": "allow",
	"reason": allow_reason,
	"required": required,
	"redact": redacted_properties,
	"clearance": clearance,
} if {
	not denial
}

# A list of nodes a read returns, filtered in one evaluation: `allowed` maps each id the subject may
# see to the properties to remove from it, `denied` lists the rest. Only sensitivity decides here:
# whether the subject may read at all was decided for the request.
filter := {
	"allowed": {r.id: type_redactions(r.type) | some r in input.resources; visible(r.type)},
	"denied": sort([r.id | some r in input.resources; not visible(r.type)]),
}

allow_reason := "a member of a team that owns it may act on it as a curator" if {
	roled
	not role_permits
	ownership_permits
} else := concat("", ["permitted by the role ", concat(", ", sort(known_roles))]) if {
	roled
} else := "anonymous read of a read-only instance" if {
	anonymous_read
} else := concat("", ["the token holds ", concat(", ", required)]) if {
	count(required) > 0
} else := "nothing is required"
