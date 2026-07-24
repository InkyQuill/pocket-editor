# Backlog

## Android quality

- Audit and reduce the remaining Android lint warnings without bundling dependency upgrades together. Context: the full debug lint run passes but reports 58 warnings and 3 hints, including launcher assets, Compose API conventions, resource-name collisions, and manifest configuration. Evidence: `app/build/reports/lint-results-debug.txt`.
