# Mumbler on Docker

## Build Containers

Execute from the project's root directory.

    docker build -t mumbler -f ./docker/Dockerfile-$(dpkg --print-architecture) .

## Start Containers

    docker network create mumbler
    docker run -d --restart=always --name gpfs1 -h gpfs1 --network mumbler -e "DATADIR=/mumbler" -v "/mnt/extra/mumbler:/mumbler" -p 5441:5441 -it mumbler java -Dakka.remote.netty.tcp.hostname="gpfs1" -Dakka.remote.netty.tcp.port="5441" -jar ./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar
    docker run -d --restart=always --name gpfs3 -h gpfs3 --network mumbler -e "DATADIR=/mumbler" -v "/mnt/extra/mumbler:/mumbler" -p 5443:5443 -it mumbler java -Dakka.remote.netty.tcp.hostname="gpfs3" -Dakka.remote.netty.tcp.port="5443" -jar ./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar
    docker run -d --restart=always --name gpfs2 -h gpfs2 --network mumbler -e "DATADIR=/mumbler" -v "/mnt/extra/mumbler:/mumbler" -p 5442:5442 -it mumbler java -Dakka.remote.netty.tcp.hostname="gpfs2" -Dakka.remote.netty.tcp.port="5442" -jar ./agent/target/scala-2.12/mids_mumbler_agent-assembly-0.1.0.jar
    docker run -d --restart always --network mumbler --name api -h api -p 0.0.0.0:8092:8092 -it mumbler java -Dakka.remote.netty.tcp.hostname="api" -Dakka.remote.netty.tcp.port="2552" -jar ./mumbler/target/scala-2.12/mids_mumbler-assembly-0.1.0.jar 100 0.0.0.0:8092 gpfs1:5441 gpfs2:5442 gpfs3:5443
