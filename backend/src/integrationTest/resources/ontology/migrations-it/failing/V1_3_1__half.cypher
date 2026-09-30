// The first statement succeeds and the second cannot parse: neither may stay.
MATCH (t:Team) WHERE t.name STARTS WITH 'migrator-it-' SET t.touched = true;
THIS IS NOT CYPHER;
