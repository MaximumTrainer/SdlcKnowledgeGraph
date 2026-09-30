MATCH (t:Team) WHERE t.name STARTS WITH 'migrator-it-'
SET t.steps = coalesce(t.steps, '') + 'b';
