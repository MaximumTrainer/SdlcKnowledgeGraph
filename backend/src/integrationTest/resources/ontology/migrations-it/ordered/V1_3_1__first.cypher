// First of two, so the order they ran in is visible on the node.
MATCH (t:Team) WHERE t.name STARTS WITH 'migrator-it-'
SET t.steps = coalesce(t.steps, '') + 'a';
