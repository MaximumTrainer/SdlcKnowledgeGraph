# The agent-actions policy (#95 FR-4, FR-5): whether an agent may take an action on its own, decided
# on what the graph holds rather than on what the agent claims.
#
# An agent asks POST /api/v1/policy/evaluate with the action and the ids of the graph facts it relies
# on. The backend looks each fact up and hands this policy what the graph says about it - whether it
# exists, how sure its source was, whether a rule inferred it, how long ago it was observed, and for a
# deployment whether an earlier artifact went to the same environment - so a fact the agent made up,
# or misremembered, counts for nothing.
#
# The answer is `allow` and the reasons for it: every condition that failed when it is refused, the
# facts that satisfied it when it is not.
package sdlc.agent_actions

import rego.v1

settings := data.sdlc.config.agent_actions

decision := {
	"allow": allow,
	"policy": "agent-actions",
	"action": action_text,
	"reasons": reasons,
}

default allow := false

allow if {
	input.action in object.keys(settings)
	count(refusals) == 0
}

reasons := sort(refusals) if {
	count(refusals) > 0
} else := sort(grounds)

action_text := a if {
	is_string(input.action)
	a := input.action
} else := "(none)"

facts := object.get(input, "facts", [])

refusals contains concat("", ["no agent-actions policy decides '", action_text, "'"]) if {
	not input.action in object.keys(settings)
}

# A fact the graph does not hold is no ground for anything.
refusals contains concat("", ["fact ", f.id, " is not in the graph"]) if {
	some f in facts
	not f.found
}

# Rollback (#95 FR-5): allowed only on a causal deployment the system of record reported, recently,
# with something to roll back to.
rollback if input.action == "rollback"

deployments := [f | some f in facts; f.found; f.type == "Deployment"]

refusals contains "rollback needs the causal Deployment among the facts" if {
	rollback
	count(deployments) == 0
}

refusals contains concat("", [
	"inferred cause: ", f.id, " was inferred by a rule at confidence ", format_confidence(f.confidence),
	", not reported by its system of record",
]) if {
	rollback
	some f in facts
	f.found
	f.inferred
}

refusals contains concat("", [
	"the deployment ", f.id, " was reported at confidence ", format_confidence(f.confidence),
	", below 1.0",
]) if {
	rollback
	some f in deployments
	not f.inferred
	f.confidence < 1
}

refusals contains concat("", [
	"the deployment ", f.id, " was observed ", format_int(f.ageMinutes, 10),
	" minutes ago, more than ", format_int(settings.rollback.max_age_minutes, 10),
]) if {
	rollback
	some f in deployments
	f.ageMinutes > settings.rollback.max_age_minutes
}

refusals contains concat("", ["no earlier artifact was deployed to ", environment_text(f), " to roll back to"]) if {
	rollback
	some f in deployments
	not f.priorArtifact
}

grounds contains concat("", [
	"the deployment ", f.id, " was reported at confidence 1.0, ", format_int(f.ageMinutes, 10),
	" minutes ago, and an earlier artifact went to ", environment_text(f),
]) if {
	rollback
	some f in deployments
}

environment_text(f) := e if {
	is_string(f.environment)
	e := f.environment
} else := "its environment"

format_confidence(c) := json.marshal(c)
