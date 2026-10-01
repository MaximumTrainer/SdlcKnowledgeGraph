# Roles (#30 FR3, FR7), for a subject whose token carries them. A subject with no roles claim is
# judged by its scopes alone, as every caller was before roles existed; one that carries the claim is
# judged by its scopes and its roles, so a role narrows what the token allows and never widens it.
#
# The roles and what each permits are data (config/data.json), the same for users, services and
# agents. Owning a resource adds the ownership role's actions on that resource: a member of a team
# that owns a repository may curate it whatever their global role.
package sdlc.authz

import rego.v1

roled if is_array(input.subject.roles)

known_roles := {r | some r in input.subject.roles; data.sdlc.config.roles[r]}

role_permits if {
	some r in known_roles
	input.action in data.sdlc.config.roles[r].actions
}

ownership_permits if {
	owner := data.sdlc.config.roles[data.sdlc.config.ownership_role]
	input.action in owner.actions
	some team in object.get(input.subject, "teams", [])
	team in object.get(input.resource, "ownerTeams", [])
}

role_denial := {"policy": "roles", "reason": "none of the subject's roles is one the policy knows"} if {
	roled
	count(known_roles) == 0
}

role_denial := {
	"policy": "roles",
	"reason": concat("", ["the role ", concat(", ", sort(known_roles)), " may not ", input.action, resource_text]),
} if {
	roled
	count(known_roles) > 0
	not role_permits
	not ownership_permits
}

resource_text := concat("", [" ", input.resource.type]) if {
	is_string(input.resource.type)
} else := ""
