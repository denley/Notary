# Change Log
All notable changes to this project will be documented in this file.
This project adheres to [Semantic Versioning](http://semver.org/).

## 0.1.4
### Fixed
- `DirectoryObserver` now clears the old adapter data before creating more.
- `DirectoryObserver` can now be filtered
- Directories are now created when they don't exist
- Fixed crash when accessing data directory
- Can now specify an external path in `DirectoryObserver`
- Directory observer now properly observes only transactions in the source directory

## 0.1.0
### Added
- Initial release