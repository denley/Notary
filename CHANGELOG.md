# Change Log
All notable changes to this project will be documented in this file.
This project adheres to [Semantic Versioning](http://semver.org/).

## 0.2.x
### Added
- Callback for `DirectoryObserver` to notify when initial sync is complete
- Transactions are actioned one by one instead of in parallel (fixes some stability issues)
- Now reports disk capacity
- Batching multiple file transfers into a single transaction
- Retry file list requests every 10 seconds

## 0.1.x
### Fixed
- `DirectoryObserver` now clears the old adapter data before creating more.
- `DirectoryObserver` can now be filtered
- Directories are now created when they don't exist
- Fixed crash when accessing data directory
- Can now specify an external path in `DirectoryObserver`
- Directory observer now properly observes only transactions in the source directory
- Directory observer can now sort files with a custom comparator
- Directory observer now records sync state with remote device
- Autosyncable items

## 0.1.0
### Added
- Initial release