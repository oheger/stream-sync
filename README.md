# Stream Sync
Stream Sync is a tool to update one directory structure to become a copy of 
another structure. Both structures are compared. Files or directories existing 
in the source structure, but not in the destination structure are created
there. Files that have been modified in the source structure override their
counterparts in the destination structure. Files or directories that exist in
the destination structure, but not in the source structure are removed.

## Usage
The tool offers a command line interface (CLI) that is invoked using the
following general syntax:

``Sync [options] <sourceDirectory> <destinationDirectory>``

where _sourceDirectory_ points to the source structure and 
_destinationDirectory_ refers to the destination structure. After a successful
execution, changes have been applied to _destinationDirectory_ , so that it is
now a copy of the source structure.

### Option syntax
A number of options is supported to customize a sync process. Options are
distinguished from the source and destination URIs by the fact that they have 
to start with the prefix `--`. Each option has a value that is obtained from 
the parameter that follows the option key. So a sequence of command line 
options looks like

``--option1 option1_value --option2 option2_value``

Options without a value or unrecognized option keys cause the program to fail
with a corresponding error message.

The options supported are described in detail below. There is one special
option, `--file`, that expects as value a path to a local file. This file is
read line-wise, and the single lines are added to the sequence of command line
arguments as if they had been provided by the user on program execution. For
instance, given a file `sync_params.txt` with the following content:

```--actions
actionCreate,actionOverride

--filter-create
exclude:*.tmp
```

Then an invocation of

`Sync --file sync_params.txt /path/source /path/dest`

would be equivalent to the following call

`Sync --actions actionCreate,actionOverride --filter-create exclude:*.tmp /path/source /path/dest`

An arbitrary number of command line files can be specified, and they can be
nested to an arbitrary depth. Note, however, that the order in which such files
are processed is not defined. This is normally irrelevant, but can be an issue
if the source and destination URIs are specified in different files. It could
then be the case that the URIs swap their position, and the sync process is 
done in the opposite direction!

Option keys are not case-sensitive; so `--actions` has the same meaning as
`--ACTIONS` or `--Actions`.

### Filtering options
With this group of options specific files or directories can be included or
excluded from a sync process. It is possible to define such filters globally,
and also for different _sync actions_. A sync process is basically a sequence
of the following actions, where each action is associated with a file or 
folder:

* Action _Create_: An element is created in the destination structure.
* Action _Override_: An element from the source structure replaces a 
  corresponding element in the destination structure.
* Action _Remove_: An element is removed from the destination structure.

To define such action filters, a special option keyword is used whose value is
a filter expression. As option keywords can be repeated, an arbitrary number of
expressions can be set for each action. A specific action on an element is 
executed only if the element is matched by all filter expressions defined for
this action. The following option keywords exist (filter expressions are 
discussed a bit later):

| Option | Description |
| ------ | ----------- |
| --filter-create | Defines a filter expression for actions of type _Create_. |
| --filter-override | Defines a filter expression for actions of type _Override_. |
| --filter-remove | Defines a filter expression for actions of type _Remove_. |
| --filter | Defines a filter expression that is applied for all action types. |

In addition, it is possible to enable or disable specific action types for the
whole sync process. Per default, all action types are active. With the
`--actions` option the action types to enable can be specified. The option 
accepts a comma-separated list of action names; alternatively, the option can
be repeated to enable multiple action types. Valid names for action types are
_actionCreate_, _actionOverride_, and _actionRemove_ (case is again ignored).

So the following option enables only create and override actions:
`--actions actionCreate,actionOverride`

With the following command line only create and remove actions are enabled:
`--actions actionCreate --actions actionRemove`

### Filter expressions
During a sync process, for each action it is checked first whether its type is
enabled. If this is the case, the filter expressions (if any) assigned to this
action type are evaluated on the element that is subject to this action. Only
if all expressions accept the element, the action is actually performed on this
element.

Thus, filter expressions refer to attributes of elements. The general syntax of
an expression is as follows:

`<criterion>:<value>`

Here _criterion_ is one of the predefined filter criteria for attributes of
elements to be synced. The _value_ is compared to a specific attribute of the
element to find out whether the criterion is fulfilled.

The following table gives an overview over the filter criteria supported:

| Criterion | Data type | Description | Example |
| --------- | --------- | ----------- | ------- |
| minlevel | Int | Each element (file or folder) is assigned a level, which is the distance to the root folder of the source structure. Files or folders located in the source folder have level 0, the ones in direct sub folders have level 1 and so on. With this filter the minimum level can be defined; so only elements with a level greater or equal to this value are taken into account. | min-level:1 |
| maxlevel | Int | Analogous to _minlevel_, but defines the maximum level; only elements with a level less or equal to this value are processed. | max-level:5 |
| exclude | Glob | Defines a file glob expression for files or folders to be excluded from the sync process. Here file paths can be specified that can contain the well-known wildcard characters '?' (matching a single character) and '*' (matching an arbitrary number of characters). | `exclude:*.tmp` excludes temporary files; `exclude:*/build/*` excludes all folders named _build_ on arbitrary levels. |
| include | Glob | Analogous to _exclude_, but defines a pattern for files to be included. | `include:project1/*` only processes elements below _project1_ |
| date-after | date or date-time | Allows to select only files whose last-modified date is equal or after to a given reference date. The reference date is specified in ISO format with an optional time portion. If no time is defined, it is replaced by _00:00:00_. | `date-after:2018-09-01T22:00:00` ignores all files with a modified date before this reference date. |
| date-before | date or date-time | Analogous to _date-after_, but selects only files whose last-modified time is before a given reference date. | `date-before:2018-01-01` will only deal with files that have been modified before 2018. |

### Examples and use cases
**Do not remove archived data**

Consider the case that a directory structure stores the data of different
projects: the top-level folder contains a sub folder for each project; all
files of this project are then stored in this sub folder and in further sub sub 
folders.

On your local hard-disk you only have a subset of all existing projects, the
ones you are currently working on. On a backup medium all project folders 
should be saved.

Default sync processes are not suitable for this scenario because they would
remove all project folders from the backup medium that are not present in the
source structure. This can be avoided by using the `min-level` filter as 
follows:

`Sync /path/to/projects /path/to/backup --filter-remove min-level:1`

This filter statement says that on the top-level of the destination structure
no remove operations are executed. For the example at hand the effect is that
folders for projects not available in the source structure will not be removed.
In the existing folders, however, (which are on level 1 and greater) full sync 
operations are applied; so all changes done on a specific project folder are
transferred to the backup medium.

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
