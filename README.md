# Stream Sync
Stream Sync is a tool to update one directory structure to become a copy of 
another structure. Both structures are compared. Files or directories existing 
in the source structure, but not in the destination structure are created
there. Files that have been modified in the source structure override their
counterparts in the destination structure. Files or directories that exist in
the destination structure, but not in the source structure are removed.

## Usage
The tool is invoked from the command line using the following syntax:

``Sync <sourceDirectory> <destinationDirectory>``

where _sourceDirectory_ points to the source structure and 
_destinationDirectory_ refers to the destination structure. After a successful
execution, changes have been applied to _destinationDirectory_ , so that it is
now a copy of the source structure. 

## Architecture
The Stream Sync tool makes use of [Reactive streams](http://www.reactive-streams.org/)
in the implementation of [Akka](https://akka.io/) to perform sync operations.
Both the source and the destination structure are represented by a stream source
emitting objects that represent the contents of the structure (files and 
folders). A special graph stage implementation contains the actual sync
algorithm. It compares two elements from the sources (which are expected to
arrive in a defined order) and decides which action needs to be performed (if
any) to keep the structures in sync. This stage produces a stream of
``SyncOperation`` objects.

So far only a description of the actions to be performed has been created. In
a second step, the ``SyncOperation`` objects are interpreted and applied to the
destination structure.

## License
Stream Sync is available under the
[Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
