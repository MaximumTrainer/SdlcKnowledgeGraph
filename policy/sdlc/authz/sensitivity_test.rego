# Sensitivity (#30 FR4, FR5, FR9): a type above the subject's clearance is hidden, a property above
# it is redacted, and an endpoint that cannot filter answers only a subject cleared for everything.
package sdlc.authz_test

import data.sdlc.authz
import rego.v1

fixture := {"version": "test", "types": {
	"Public": {"sensitivity": "public", "properties": {}},
	"Internal": {"sensitivity": "internal", "properties": {"secret": "restricted", "note": "confidential"}},
	"Confidential": {"sensitivity": "confidential", "properties": {}},
	"Restricted": {"sensitivity": "restricted", "properties": {}},
}}

reader(role) := {"id": "s", "kind": "user", "scopes": ["graph:read"], "roles": [role], "teams": []}

read_of(subject, type) := d if {
	d := authz.decision with input as {
		"subject": subject, "action": "read",
		"resource": {"kind": "node", "type": type},
	}
		with data.sdlc.ontology as fixture
}

# Role x sensitivity: what each clearance reads.
test_each_clearance_reads_up_to_its_level if {
	cleared := {
		"viewer": {"Public", "Internal"},
		"operator": {"Public", "Internal", "Confidential"},
		"admin": {"Public", "Internal", "Confidential", "Restricted"},
		"agent-reader": {"Public", "Internal"},
	}
	every role, types in cleared {
		every type in {"Public", "Internal", "Confidential", "Restricted"} {
			readable := type in types
			read_of(reader(role), type).allow == readable
		}
	}
}

test_a_hidden_type_is_refused_naming_its_level if {
	d := read_of(reader("viewer"), "Restricted")
	not d.allow
	d.policy == "sensitivity"
	d.reason == "Restricted is restricted, above what the subject is cleared for (internal)"
}

test_properties_above_the_clearance_are_redacted if {
	read_of(reader("viewer"), "Internal").redact == ["note", "secret"]
	read_of(reader("operator"), "Internal").redact == ["secret"]
	read_of(reader("admin"), "Internal").redact == []
}

test_the_real_registry_redacts_an_incident_summary_from_a_viewer if {
	d := authz.decision with input as {
		"subject": reader("viewer"), "action": "read",
		"resource": {"kind": "node", "type": "Incident"},
	}
	d.allow
	d.redact == ["shortDescription"]
}

test_the_real_registry_hides_service_principals_from_a_viewer if {
	d := authz.decision with input as {
		"subject": reader("viewer"), "action": "read",
		"resource": {"kind": "node", "type": "ServicePrincipal"},
	}
	not d.allow
	contains(d.reason, "restricted")
}

test_a_list_is_filtered_and_redacted_in_one_answer if {
	f := authz.filter with input as {
		"subject": reader("viewer"), "action": "read",
		"resources": [
			{"id": "a", "type": "Internal"},
			{"id": "b", "type": "Restricted"},
			{"id": "c", "type": "Public"},
		],
	}
		with data.sdlc.ontology as fixture
	f.allowed == {"a": ["note", "secret"], "c": []}
	f.denied == ["b"]
}

test_a_subject_whose_roles_are_all_unknown_sees_nothing if {
	f := authz.filter with input as {
		"subject": reader("superuser"), "action": "read",
		"resources": [{"id": "c", "type": "Public"}],
	}
		with data.sdlc.ontology as fixture
	f.denied == ["c"]
}

test_an_endpoint_that_cannot_filter_answers_only_full_clearance if {
	unfiltered := {"kind": "endpoint", "filtered": false}
	d := authz.decision with input as {"subject": reader("viewer"), "action": "read", "resource": unfiltered}
	not d.allow
	d.policy == "sensitivity.unfiltered"
	authz.decision.allow with input as {"subject": reader("admin"), "action": "read", "resource": unfiltered}
	authz.decision.allow with input as {"subject": reader("viewer"), "action": "read", "resource": {"kind": "endpoint", "filtered": true}}
}

test_writing_a_type_needs_clearance_for_it if {
	curator := {"id": "s", "kind": "user", "scopes": ["graph:write"], "roles": ["curator"], "teams": []}
	not authz.decision.allow with input as {"subject": curator, "action": "create", "resource": {"kind": "node", "type": "ServicePrincipal"}}
	authz.decision.allow with input as {"subject": curator, "action": "create", "resource": {"kind": "node", "type": "Team"}}
}
