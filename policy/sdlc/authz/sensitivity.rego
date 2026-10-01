# Sensitivity (#30 FR4, FR5): how sensitive each node type and property is, from the ontology
# registry (ontology/data.json, generated from nodes.yaml), and how far a subject is cleared to see.
#
# A node of a type above the subject's clearance is not shown at all; a property above it is
# redacted from a node that is. An endpoint that cannot filter what it returns is answered only for
# a subject cleared for everything the ontology declares.
#
# The default clearance of a subject without roles, and of an anonymous reader, is the highest level
# (config/data.json), so by default nothing is hidden that was visible before sensitivity existed.
package sdlc.authz

import rego.v1

rank(level) := i if {
	some i, l in data.sdlc.config.levels
	l == level
}

type_level(t) := l if {
	l := data.sdlc.ontology.types[t].sensitivity
} else := "internal"

type_redactions(t) := sort([p |
	some p, l in object.get(data.sdlc.ontology.types, [t, "properties"], {})
	rank(l) > clearance_rank
])

visible(t) if rank(type_level(t)) <= clearance_rank

# The most sensitive level anything in the ontology declares.
max_rank := max({rank(t.sensitivity) | some t in data.sdlc.ontology.types} | {rank(l) |
	some t in data.sdlc.ontology.types
	some l in object.get(t, "properties", {})
})

clearance_rank := r if {
	roled
	r := max({rank(data.sdlc.config.roles[x].clearance) | some x in known_roles} | {-1})
} else := rank(data.sdlc.config.anonymous_clearance) if {
	input.subject.kind == "anonymous"
} else := rank(data.sdlc.config.unroled_clearance)

clearance := data.sdlc.config.levels[clearance_rank] if {
	clearance_rank >= 0
} else := "none"

node_resource if {
	input.resource.kind in {"node", "edge"}
	is_string(input.resource.type)
}

level_of_resource := type_level(input.resource.type) if input.resource.kind == "node"

level_of_resource := data.sdlc.ontology.edges[input.resource.type] if input.resource.kind == "edge"

redacted_properties := type_redactions(input.resource.type) if {
	input.resource.kind == "node"
	is_string(input.resource.type)
} else := []

sensitivity_denial := {
	"policy": "sensitivity",
	"reason": concat("", [
		input.resource.type, " is ", level_of_resource,
		", above what the subject is cleared for (", clearance, ")",
	]),
} if {
	node_resource
	rank(level_of_resource) > clearance_rank
}

sensitivity_denial := {
	"policy": "sensitivity.unfiltered",
	"reason": concat("", [
		"this endpoint cannot redact by sensitivity, and the subject is cleared for ", clearance,
		", below the most sensitive data it may return (", data.sdlc.config.levels[max_rank], ")",
	]),
} if {
	input.action in reads
	input.resource.kind == "endpoint"
	object.get(input.resource, "filtered", false) == false
	clearance_rank < max_rank
}
