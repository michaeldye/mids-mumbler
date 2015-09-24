# mids-mumbler
Akka-based implementation of Mumbler for MIDS Scaling Up! homework

## (rough!) steps to execute

* Build with SBT: `sbt assembly`

* Upload `./agent/target/scala-2.11/mids_mumbler_agent-assembly-0.1.0.jar` to gpfs1, gpfs2, gpfs3. Upload `./mumbler/target/scala-2.11/mids_mumbler-assembly-0.1.0.jar` to gpfs1.

* Start mumbler agent on each node:

         java -Dakka.remote.netty.tcp.hostname="gpfs1" -Dakka.remote.netty.tcp.port="5441" -jar mids_mumbler_agent-assembly-0.1.0.jar
         java -Dakka.remote.netty.tcp.hostname="gpfs2" -Dakka.remote.netty.tcp.port="5442" -jar mids_mumbler_agent-assembly-0.1.0.jar
         java -Dakka.remote.netty.tcp.hostname="gpfs3" -Dakka.remote.netty.tcp.port="5443" -jar mids_mumbler_agent-assembly-0.1.0.jar

* Execute mumbler on head node (gpfs1 in my case), for example, with seed word `fruit` and max chain `10`:

         time java -jar mids_mumbler-assembly-0.1.0.jar fruit 10

On first execution, the mumbler will download and preprocess input files while it streams them. This means that the first invocation will take approximately 90 minutes to return. On each subsequent invocation, the mumbler will instruct the agents to download the files again and, if they exist, use the cached copies instead of re-downloading.

**Note**: The organization of data by this program can really eat inodes on an FS. If writing files to GPFS w/ 3x25GB clustered disks, you need to create the filesystem with a lot of inodes, e.g.: ` mmcrfs gpfsfpo -F /root/diskfile.fpo -A yes -Q no -r 1 -R 1 --inode-limit 5M`.

## Sample output

- `time java -jar mids_mumbler-assembly-0.1.0.jar taco 10`:

[INFO] [06/09/2015 16:50:06.100] [Mumbler-akka.actor.default-dispatcher-17] [akka.tcp://Mumbler@gpfs1:45490/user/Conductor] Exiting b/c reached requested max chain length, 10
[INFO] [06/09/2015 16:50:06.100] [Mumbler-akka.actor.default-dispatcher-17] [akka.tcp://Mumbler@gpfs1:45490/user/Conductor] Chain: taco chips can infallibly inspired my accountability so legislative expediency

real  0m3.226s

- `time java -jar mids_mumbler-assembly-0.1.0.jar leprechaun 200`:

[INFO] [06/09/2015 16:50:50.559] [Mumbler-akka.actor.default-dispatcher-4] [akka.tcp://Mumbler@gpfs1:35166/user/Conductor] Exiting b/c reached requested max chain length, 200
[INFO] [06/09/2015 16:50:50.559] [Mumbler-akka.actor.default-dispatcher-4] [akka.tcp://Mumbler@gpfs1:35166/user/Conductor] Chain: leprechaun named Maya peasant farmsteads have movement anticipated debate followed thick brake that subjugate Japan Japan export decline these anger using creature thai God upholds His brook can denounce fellow Gypsies seem lived till be wakeful life die cases additional spherical sacs called tenants holding immediate vicinage where l while specification indicates combination and Wasko and Copt Hall Recorder Players then distended kidney generally an Williams examines spatial side to aeons to gunplay in Iturea and spasm after Huysmans had l5 the anthor as multiprocessing applications cause easily performable in Xosa and Waterland and Kerak of Eeform Act well upward before infusion containing itself increasingly dispersed equally swift decay induced declines their pyrogenic action unworthy person allowed finally resorting too revolutionary version Version used ae have narrated might liberate women exposed steam when lying fame is parasitical relationship indefinitely near some experiments becomes unconfined by lotions that pink trunk immediately appear symmetrically opposed Franklin Sav Bank Court judges explained sorrowfully into abject look objectively viewed generally supplanted human body sits toward Moab in vrhich they torn another complex child reverts spontaneously but feu qu is clouded By contributing one tricks was beside Dick Pope visited each feared placing unreasonable upon

real  0m10.672s

- `time java -jar mids_mumbler-assembly-0.1.0.jar fallible 100`:

[INFO] [06/09/2015 16:51:57.469] [Mumbler-akka.actor.default-dispatcher-18] [akka.tcp://Mumbler@gpfs1:51433/user/Conductor] Exiting b/c no following words found
[INFO] [06/09/2015 16:51:57.471] [Mumbler-akka.actor.default-dispatcher-18] [akka.tcp://Mumbler@gpfs1:51433/user/Conductor] Chain: fallible disciples forth respecting boy revived public knowl and Apame

real  0m3.826s
