# mids-mumbler

Akka-based implementation of a Markov chain generator from Google n-gram data sets. This is an implementation of the Mumbler assignment in the MIDS Scaling Up! Data Science Curriculum.

## System Building and Launch

### Preconditions

* Obtain a recent version of SBT (as of this writing, v.1.0.2 on Linux was used, it was fetched from http://www.scala-sbt.org/download.html)
* Configure network connectivity between each box that will host the system and ensure that the configured hostnames are resolvable (see example invocation below for use of ports and hostnames given at invocation)

### Steps

* Build with SBT: `sbt assembly`

* Upload `./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar` to remote nodes (perhaps gpfs1, gpfs2, and gpfs3). Upload `./mumbler/target/scala-2.12/mids_mumbler-assembly-0.1.0.jar` to a system from which you can make websocket requests and that has network access to the remote nodes (this hosts the websocket API). Upload the `ui` directory to this system also.

* Create a word censorship list at location `DATADIR/badwords.txt` (see next step for the use of this variable). If you prefer not to censor output, an empty badwords file is acceptable.

* Start mumbler agent on each node:

      DATADIR=/vol/ngram/gpfs1 java -Dakka.remote.netty.tcp.hostname="gpfs1" -Dakka.remote.netty.tcp.port="5441" -jar ./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar
      DATADIR=/vol/ngram/gpfs2 java -Dakka.remote.netty.tcp.hostname="gpfs2" -Dakka.remote.netty.tcp.port="5442" -jar ./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar
      DATADIR=/vol/ngram/gpfs3 java -Dakka.remote.netty.tcp.hostname="gpfs3" -Dakka.remote.netty.tcp.port="5443" -jar ./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar

* Execute API launcher, providing configuration for the number of n-gram source files to process (100), an address and port to which to bind a websocket API (0.0.0.0:8080), and the hostname and address of each remote agent (gpfs1:5442...). The envvar points to the `ui` directory in the project root:

      MARKOV_UI=$PWD/ui; java -Dakka.remote.netty.tcp.hostname="api" -Dakka.remote.netty.tcp.port="2552" -jar ./mumbler/target/scala-2.12/mids_mumbler-assembly-0.1.0.jar 100 0.0.0.0:8080 gpfs1:5442 gpfs2:5442 gpfs3:5442

On first execution of the API launcher, the remote agents will download and preprocess input files as they are streamed (the full corpus is distributed among the agents). This means that the first invocation will take approximately 90 minutes to be ready to serve requests (if you'd like to test the system with fewer source files, replace the quantity "100" in the above invocation with a smaller value). On each subsequent invocation, the agents will report that the files have already been processed.

Only after the full data set is fetched and processed will the API be available to queries.

**Note**: The organization of data by this program can really eat inodes on an FS. If writing files to GPFS w/ 3x25GB clustered disks, you need to create the filesystem with a lot of inodes, e.g.: ` mmcrfs gpfsfpo -F /root/diskfile.fpo -A yes -Q no -r 1 -R 1 --inode-limit 5M`.

## System Use

This system serves a websocket API. Given a chain length limit integer and a chain seed word the system will build a Markov chain using remote agents. Each word added to the chain will be published to the websocket. When the chain is fully-formed the socket will be closed.

Example invocation where "20" is the chain limit and "fruit" is the seed word:

    mdye@heidegger:~[100043]# wscat --connect "ws://localhost:8092/chain/20/seed/fruit"
    connected (press CTRL+C to quit)
    < fritters
    < served
    < much
    < departed
    < leaves
    < disappear
    < easily
    < perceived
    < unless
    < storage
    < receptacles
    < could
    < recreate
    < only
    < purveyors
    < was
    < transgressed
    < my
    < neck
    disconnected

A query UI is available if you browse to `http://localhost:8092/ui/`. The UI accepts parameters to automatically perform searches and limit the chain length, for example: `http://localhost:8092/ui/?searchinterval=6000&chainmax=60`.

Logging output from the nodes and API runtimes varies. The API logs will print chain words as they are selected and the complete chain upon termination:

    ...
    13:31:06.469 [main] INFO mumbler.Launch$ - API listening on 0.0.0.0:8092
    13:31:06.482 [Mumbler-akka.actor.default-dispatcher-4] INFO mumbler.API - API server binding complete
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] fritters
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] served
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] much
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] departed
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] leaves
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] disappear
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] easily
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] perceived
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] unless
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] storage
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] receptacles
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] could
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] recreate
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] only
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] purveyors
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] was
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] transgressed
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] my
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] neck
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] Exiting b/c reached requested max chain length, 20
    [INFO] ... [akka.tcp://Mumbler@api:2552/user/StreamSupervisor-0/flow-2-1-actorPublisherSource] Chain: fruit fritters served much departed leaves disappear easily perceived unless storage receptacles could recreate only purveyors was transgressed my neck
