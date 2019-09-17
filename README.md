# Stream Sync
Stream Sync is a tool to update one directory structure to become a copy of 
another structure. Both structures are compared. Files or directories existing 
in the source structure, but not in the destination structure are created
there. Files that have been modified in the source structure override their
counterparts in the destination structure. Files or directories that exist in
the destination structure, but not in the source structure are removed.

## Building
Stream Sync uses [sbt](https://www.scala-sbt.org/) as its build tool. After the
project has been checked out, you can cd into the project directory and enter 
the sbt console by typing `sbt`. Then the following commands are of interest:
* `test` compiles everything and executes the unit tests
* `integrationTest:test` compiles everything and executes the integration tests
* `assembly` as the build makes use of the
  [sbt-assembly plugin](https://github.com/sbt/sbt-assembly), with this command
  a so-called fat jar can be built that is executable and contains all the
  dependencies of Stream Sync. This makes it easy to run the tool from the
  command line without having to bother with a large class path.

## Usage
The tool offers a command line interface (CLI) that is invoked using the
following general syntax:

``Sync [options] <sourceStructure> <destinationStructure>``

where _sourceStructure_ points to the structure serving as the source of the
sync process, and _destinationDirectory_ refers to the destination structure. 
After a successful execution, changes have been applied to 
_destinationDirectory_ , so that it is now a copy of the source structure.

_Note:_ Being written in Scala, Stream Sync requires a Java Virtual Machine to
run. So the full command to be executed has to launch Java and specify the full
class path and the main class (which is _com.github.sync.cli.Sync_). When a fat
jar has been built as described in the _Building_ section the command can be
abbreviated to

``java -jar stream-sync-assembly-<version>.jar [options] <source> <destination>``

In all examples in this document the short form ``Sync`` is used as a 
placeholder for the complete command.

### Structures
The generic term _structure_ has been used to refer to the source and the
destination of a sync process. The reason for this is that Stream Sync can
handle different types of structures. In the most basic case, the structures
are paths on a local file system (or a network share that can be accessed in 
the same way as a local directory). In this case, the paths can be specified
directly.

To reference a different type of structure, specific prefixes need to be used.
The meaning of the part after this prefix is then specific to the structure
type; it can be for instance a URL pointing to a server on the internet.

Currently, only a single prefix is supported: _dav:_. This prefix indicates
that the structure is hosted on a WebDav server. The root URL to the directory
on the server to be synced must be specified after the prefix. The following
example shows how to sync a path on the local file system with a directory on a
WebDav server:

``Sync [options] /data/local/music dav:https://my.cloud-space.com/data/music``

Some structures need additional parameters to be accessed correctly. For 
instance, a WebDav server typically requires correct user credentials. Such
parameters are passed as additional options in the command line; they are
allowed only if a corresponding structure takes part in the sync process. A
structure requiring additional parameters can be both the source and the
destination of the sync process; therefore, when providing additional options
it must be clear to which structure they apply. This is achieved by using
special prefixes: ``src-`` for options to be applied to the source structure,
and ``dst-`` for options referring to the destination structure. In the example
above the WebDav structure is the destination; therefore, the user name and
password options must be specified using the ``dst-`` prefix:

```
Sync --dst-user myWebDavUserName --dst-password myWebDavPwd \
   /data/local/music dav:https://my.cloud-space.com/data/music
```

If both structures were WebDav directories, one would also have to specify the
corresponding options with the ``src-`` prefix, as in

```
Sync dav:https://server1.online.com/source \ 
  --src-user usrIDSrc --src-password pwdSrc \
  dav:https://server2.online.com/dest \
  --dst-user usrIDDst --dst-password pwdDst
```

This convention makes it clear, which option applies to which structure. The
structure types supported are described in more detail later in this document.
It is then listed for each structure type which additional options it supports.

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

### Apply modes
Per default the sync process determines the delta between the source structure
and the destination structure (whose URIs are specified on the command line)
and then applies the resulting sync operations to the destination structure.
That way the destination structure becomes a mirror of the source structure.

It is possible to change this behavior by specifying the `--apply` option. The
option can have one of the following values (case does not matter):

| Apply mode | Description |
| ---------- | ----------- |
| TARGET | This is the default apply mode causing the behavior as described above. |
| TARGET:URI | Works like the default _TARGET_ mode, but the sync operations are applied to the structure defined by the URI. This can be a different URI than the URI of the destination structure. This is useful for instance if a structure should be mirrored to multiple backup locations. Then the delta between the source and destination structure can be applied to alternative target structures as well. |
| NONE | In this mode no sync operations are executed at all. This mainly makes sense when a log file is written (see below); then a sync process can be executed in a _dry-run_ mode in which no actions are performed, but the operations that would be executed can be seen in the log file. |

*Attention*: The implementation of the apply mode `TARGET:URI` is currently
limited. It can only be used when syncing file systems and file names are not
encrypted. 

### Sync log files
The sync operations executed during a sync process can also be written in a 
textual representation to a log file. This is achieved by adding the `--log`
option whose value is the path to the log file to be written.

It is also possible to use such a log file as input for another sync process.
Then the sync operations to be executed are not calculated as the delta between
two structures, but are directly read from the log file. This is achieved by
specifying the `--sync-log` option whose value is the path to the log file to
be read. Note that in this mode still the URIs for both the source and 
destination structure need to be specified; log files contain only relative 
URIs, and in order to resolve them correctly the root URIs of the original
structures must be provided.

If the structures to be synced are pretty complex and/or large files need to
be transferred over a slow network connection, sync processes can take a while.
With the support for log files this problem can be dealt with by running 
multiple incremental sync operations. This works as follows:

1. An initial sync process is run for the structures in question that has the
   `--log` option set and specifies an apply mode of `None`. This does not
   execute any actions, but creates a log file with the operations that need to
   be done. 
2. Now further sync processes can be started to process the sync log written in
   the first step. For such operations the following options must be set:
   * `--sync-log` is set to the path of the log file written in the first step.
   * `--log` is set to a file keeping track on the progress of the overall 
     operation. This file is continuously updated with the sync operations that
     have been executed.
   
   The sync processes can now be interrupted at any time and resumed again
   later. When restarted with these options the process ignores all sync
   operations listed in the progress log and only executes those that are still
   pending. This is further outlined in the _Examples_ section.
   
### Adjust granularity of timestamps
In order to decide whether a file needs to be copied to the destination
structure, StreamSync compares the last-modified timestamps of the files
involved. After a file has been copied, the timestamp in the destination
structure is updated to match the one in the source structure; so if there are
no changes on the file in the source structure, another sync process will 
ignore this file - at least in theory.

In practice there can be some surprises when syncing between different types of
file systems or structures. The differences can also impact the comparison of
last-modified timestamps. For instance, some structures may store such
timestamps with a granularity of nanoseconds, while others only use seconds.
This may lead to false positives when StreamSync decides which files to copy.

To deal with problems like that, the `--ignore-time-delta` option can be
specified. The option expects a numeric value which is interpreted as a
threshold in seconds for an acceptable time difference. So if the difference
between the timestamps of two files is below this threshold, the timestamps 
will be considered to be equal. Setting this option to a value of 1 or 2
should solve all issues related to the granularity of file timestamps. An
example using this option can be found in the _Examples and use cases_
section.

### Encryption
One use case for StreamSync is creating a backup of a local folder structure 
on a cloud server; the data is then duplicated to another machine that is
reachable from everywhere. However, if your data is sensitive, you probably do
not want it lying around on a public server without additional protection.

StreamSync offers such protection by supporting multiple options for encrypting
the data that is synced:
* The content of files can be encrypted.
* The names of files and folders can be encrypted.

If encryption is used and what is encrypted is controlled by the so-called
_encryption mode_. This is an enumeration that can have the following values:
* _none_: No encryption is used.
* _files_: The content of files is encrypted.
* _filesAndNames_: Both the content of files and their names are encrypted.
  (This includes directories as well.)

In all cases, encryption is based on 
[AES](https://en.wikipedia.org/wiki/Advanced_Encryption_Standard) using key
sizes of 128 bits. The keys are derived from password strings that are
transformed accordingly (password strings shorter than 128 bits are padded,
longer strings are cut). In addition, a random initialization vector is used;
so an encrypted text will always be different, even if the same input is
passed.

The source and the destination of a sync process can be encrypted 
independently. If an encryption mode other than _none_ is set for the destination, 
but not for the source, files transferred to the destination are encrypted. If
such an encryption mode is set for the source, but not for the destination,
files are decrypted. If active encryption modes are specified for both sides,
files are decrypted first and then encrypted again with the destination 
password.

The following table lists the command line options that affect encryption (all
of them are optional):

| Option | Description | Default |
| ------ | ----------- | ------- |
| src-crypt-mode | The encryption mode for the source structure (see above). This flag controls whether encryption is applied to files on the source structure. | _none_ |
| dst-crypt-mode | The encryption mode for the destination structure; controls how encryption is applied to the destination structure. | _none_ |
| src-encrypt-password | Defines a password for the encryption of files in the source structure. This password is needed when the source crypt mode indicates that encryption should be used. | Undefined |
| dst-encrypt-password | Analogous to ``src-encrypt-password``, but a password for the destination structure is defined. It is evaluated for a corresponding encryption mode. | Undefined |
| crypt-cache-size | During a sync operation with encrypted file names, it may be necessary to encrypt or decrypt file names multiple times; for instance if parent folders are accessed multiple times to process their sub folders. As an optimization, a cache is maintained storing the names that have already been encrypted or decrypted; that way the number of crypt operations can be reduced. For sync operations of very complex structures (with deeply nested folder structures), it can make sense to set a higher cache size. Note that the minimum allowed size is 32. | 128 |

Note that folder structures that are only partly encrypted are not supported;
when specifying an encryption password, the password is applied to all files.

### Structure types
This section lists the different types of structures that are supported for
sync processes. If not mentioned otherwise, all types can act as source and as
destination structure of a sync process. The additional parameters supported by
a structure type are described as well.

#### Local directories
This is the most basic and "natural" structure type. It can be used for 
instance to mirror a directory structure on the local hard disk to an external
hard disk or a network share.

To specify such a structure, just pass the (OS-specific) path to the root
directory without any prefix. The table below lists the additional options 
that are supported. (Remember that these options need to be prefixed with
either ``src-`` or ``dst-`` to assign them to the source or destination
structure.)

| Option | Description | Mandatory |
| ------ | ----------- | --------- |
| time-zone | There are file systems that store last-modified timestamps for files in the system's local time without proper time zone information. This causes the last-modify time to change together with the local time zone, e.g. when the daylight saving time starts or ends. In such cases, Stream Sync would consider the files on this file system as changed because their last-modified time is now different. One prominent example of such a file system is FAT32 which is still frequently used, for instance on external hard disks, because of its broad support by different operation systems. To work around this problem, with the _time-zone_ option it is possible to define a time zone in which the timestamps of files in a specific structure have to be interpreted. The last-modified time reported by the file system is then calculated according to this time zone before comparison. Analogously, when setting the last-modified time of a synced file the timestamp is adjusted. As value of the option, any string can be provided that is accepted by the [ZoneId.of()](https://docs.oracle.com/javase/8/docs/api/java/time/ZoneId.html#of-java.lang.String-) method of the _ZoneId_ JDK class. | No |

#### WebDav directories
It is possible to sync from or to a directory hosted on a WebDav server. To do
this, the full URL to the root directory on the server has to be specified with
the prefix ``dav:`` defining the structure type. The following table lists the
additional options supported for WebDav structures. (Remember that these
options need to be prefixed with either ``src-`` or ``dst-`` to assign them to
the source or destination structure.)

| Option | Description | Mandatory |
| ------ | ----------- | --------- |
| user | The user ID to log into the server. | Yes |
| password | The password to log into the server. | Yes |
| modified-property | The name of the property that holds the last-modified time of files on the server (see below). | No |
| modified-namespace | Defines a namespace to be used together with the last-modified property (see below). | No |
| delete-before-override | Determines whether a file to be overridden on the WebDav server is deleted first. Experiments have shown that for some WebDav servers override operations are not reliable; in some cases, the old file stays on the server although a success status is returned. For such servers this property can be set to *true*. StreamSync will then send a DELETE request for this file before it is uploaded again. All other values disable this mode. | No |

**Notes**
Using WebDav in sync operations can be problematic as the standard does not
define an official way to update a file's last-modified time. Files have a
_getlastmodified_ property, but this is typically set by the server to the
time when the file has been uploaded. For sync processes it is, however, 
crucial to have a correct modification time; otherwise, the file on the server
would be considered as changed in the next sync process because its timestamp
does not match the one of the file it is compared against.

Concrete WebDav servers provide different options to work around this problem.
Stream Sync supports servers that store the modification time of files in a
custom property that can be updated. The name of this property can be defined
using the ``modified-property`` option. As WebDav requests and responses are
based on XML, the custom property may use a different namespace than the 
namespace used for the core WebDav properties. In this case, the
``modified-namespace`` option can be set.

When using a WebDav directory as source structure Stream Sync will read the
modification times of files from the configured ``modified-property`` property;
if this is undefined, the standard property _getlastmodified_ is used instead.

When a WebDav directory acts as destination structure, after each file upload
another request is sent to update the file's modification time to match the one
of the source structure. Here again the configured property (with the optional
namespace) is used or the standard property if unspecified.

### Throttling sync streams

In some situations it may be necessary to restrict the number of sync 
operations that are executed in a given time unit. For instance, there are
public servers that react with an error status of _429 Too many files_ when 
many small files are uploaded over a fast internet connection.

StreamSync supports a command line option to deal with such cases:
``ops-per-second``. The option is passed a numeric value that limits the number
of sync operations (file uploads, deletion of files, creation of folders, etc.)
in a second. By specifying the minimum value of 1, only a single operation per
second is executed. This is a good solution for the problem with overloaded
servers because it mainly impacts small files and operations that complete very
fast. The upload of larger files that takes significantly longer than a second
will not be delayed by this switch.

Another option to influence the speed of sync processes that have an HTTP 
server as source or destination is to override certain configuration settings.
StreamSync uses the [Akka HTTP](https://doc.akka.io/docs/akka-http/current/introduction.html)
library for the communication via the HTTP protocol. The library can be 
[configured](https://doc.akka.io/docs/akka-http/current/configuration.html) 
in many ways, and system properties can be used to override its default 
settings. Options you may want to modify in order to customize sync streams are
the size of the pool for HTTP connections (which determines the parallelism 
possible and is set to 4 per default) or the number of requests that can be
open concurrently (32 by default). To achieve this, pass the following 
arguments to the Java VM that executes StreamSync:

``-Dakka.http.host-connection-pool.max-connections=1 -Dakka.http.host-connection-pool.max-open-requests=2``

As you can see in this example, the name of the system properties is derived
from the hierarchical structure of the configuration options for Akka HTTP as
described in the referenced documentation.

### Reading passwords from the console
For some use cases, e.g. connecting to a WebDav server or encrypting files,
StreamSync needs passwords. Per default, such passwords can be specified as
command line arguments, like any other arguments processed by the program.
This can, however, be problematic when it comes to secret data: If the program
is invoked from a command shell, the passwords are directly visible. They are
typically stored in the command line history as well. So they can be easily
compromised.

To reduce this risk, passwords can also be read from the console. This happens
automatically without any additional action required by the caller. If a
password is required for a concrete sync scenario, but the corresponding 
command line argument is missing, the user is prompted to enter it. As prompt
the name of the command line argument representing the password is used. When 
the password is typed in no echo is displayed.

It is well possible that multiple passwords are needed for a single sync
process. An example could be a process that syncs from the local file system to
an encrypted WebDav server. Then a password is needed to connect to the server,
and another one for the encryption. Either of them can be omitted from the
command line; the user is prompted for all missing passwords.

### Examples and use cases
**Sync a local directory to an external USB hard disk**

This should be a frequent use case, in which some local work is saved on an
external hard disk. The command line is pretty straight-forward, as the target
drive can be accessed like a local drive; e.g. under Windows it is assigned a
drive letter. The only problem is that if the file system on the external drive
is FAT32, it may be necessary to explicitly specify a time zone in which
last-modified timestamps are interpreted (refer to the description of local
directories for more information). For this purpose, the `time-zone` option 
needs to be provided. In addition, the `ignore-time-delta` option is set to a
value of 2 seconds to make sure that small differences in timestamps with a
granularity below seconds do not cause unnecessary copy operations.

`Sync C:\data\work D:\backup\work --dst-time-zone UTC+02:00 --ignore-time-delta 2`

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

**Interrupt and resume long-running sync processes**

As described under _Sync log files_, with the correct options sync processes
can be stopped at any time and resumed at a later point in time. The first
step is to generate a so-called _sync log_, i.e. a file containing the
operations to be executed to sync the structures in question:

`Sync /path/to/source /path/to/dest --apply NONE --log /data/sync.log`

This command does not change anything in the destination structure, but only
creates a file _/data/sync.log_ with a textual description of the operations to
execute. (Such files have a pretty straight-forward structure. Each line
represents an operation including an action and the element affected.)

Now another sync process can be started that takes this log file as input. To
keep track on the progress that is made, a second log file has to be written -
the _progress log_:

`Sync /path/to/source /path/to/dest --sync-log /data/sync.log --log /data/progress.log`

This process can be interrupted and later started again with the same command
line. It will execute the operations listed in the sync log, but ignore the 
ones contained in the progress log. Therefore, the whole sync process can be
split in a number of incremental sync processes.

**Sync from a local directory to a WebDav directory**
The following command can be used to mirror a local directory structure to an
online storage:

```
Sync C:\data\work dav:https://sd2dav.1und1.de/backup/work \
--log C:\Temp\sync.log \
--dst-user my.account --dst-password s3cr3t_PASsword \
--dst-modified-property Win32LastModifiedTime \
--dst-modified-namespace urn:schemas-microsoft-com: \
--filter exclude:*.bak
```

Here all options supported by the WebDav structure type are configured. The
server (which really exists) does not allow modifications of the standard
WebDav _getlastmodified_ property, but uses a custom property named
_Win32LastModifiedTime_ with the namespace _urn:schemas-microsoft-com:_ to
hold a modified time different from the upload time. This property will be set
correctly for each file that is uploaded during a sync process.

Note that the _--dst-password_ parameter could have been omitted. Then the user
would have been prompted for the password.

**Sync from a local directory to a WebDav server with encryption**
Building upon the previous example, with some additional options it is possible
to protect the data on the WebDav server using encryption: 

```
Sync C:\data\work dav:https://sd2dav.1und1.de/backup/work \
--log C:\Temp\sync.log \
--dst-user my.account --dst-password s3c3t_PASsword \
--dst-modified-property Win32LastModifiedTime \
--dst-modified-namespace urn:schemas-microsoft-com: \
--filter exclude:*.bak \
--dst-encrypt-password s3cr3t
--dst-crypt-mode filesAndNames
--crypt-cache-size 1024
--ops-per-second 2
```

This command specifies that both the content and the names of files are
encrypted using the password "s3cr3t" when copied onto the WebDav server. With
an encryption mode of _files_ only the files' content would be encrypted, but
the file names would remain in plain text. The size of the cache for encrypted
names is increased to avoid unnecessary crypt operations. In the example the
number of sync operations per second is limited to 2 to avoid that the server
rejects requests because its load is too high.

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
