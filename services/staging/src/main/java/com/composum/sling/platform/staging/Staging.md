# PlatformVersionsService.getStatus

## Detailed design specs of some functions

### PlatformVersionService.getStatus and PlatformVersionService.findWorkspaceChanges 
compare the workspace and the release: status of page and status in "Modified" list of release

Workspace | Workspace in Release? | Release | Status
--- | --- | --- | ---
unmodified or modified | no | not there | initial 
unmodified | yes | inactive | activated
modified | yes | inactive | modified
unmodified or modified or moved | yes | deactivated | deactivated
deleted | - | active or inactive | deleted
moved | yes | inactive | deactivated
moved | yes | active | modified
deleted and recreated at same path | no | active | modified
deleted and recreated at same path | no | inactive | deactivated
deleted and something else moved to same path | at different path | active | modified?
deleted and something else moved to same path | at different path | inactive | deactivated?

??? what's if the old versionable is moved away but exists, and the new versionable is moved from somewhere else?
Restriction that the released versionable must not be present in the workspace, and the versionable in the workspace must not be present in the release? But maybe that's OK, anyway.

### PlatformVersionService.findReleaseChanges
compare release with (a / the) previous release. "modified" means different version or at different path than in previous release. "(in)active different versionable" means there is a new versionable, which wasn't present at the previous path, at the same path.

Release | previous Release | Status
--- | --- | ---
active | not there | initial?
inactive | not there | (not listed)
active (modified or not)) | inactive | activated
active + modified | active | modified
inactive | inactive, active | deactivated
active different versionable | inactive | activated 
active different versionable | active | modified
inactive different versionable | active or inactive | deactivated
