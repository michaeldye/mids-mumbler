#!/bin/bash

set +e

HERE="$(realpath $(dirname ${BASH_SOURCE[0]}))"
BASE=$(realpath $HERE/..)

TMP_ROOT=$(mktemp -d)
VERSION=$(grep -Po 'version := "\K.*' $BASE/build.sbt | awk -F\" '{print $1}')
TMP="$TMP_ROOT/$VERSION"

cleanup() {
  rm -Rf $TMP_ROOT
}

trap cleanup INT QUIT TERM EXIT

cd $BASE
sbt clean assembly

if [ "$?" -ne 0 ]; then
  exit $?
fi

mkdir -p $TMP
cp -Rvf ./ui $TMP
find . -iname "*.jar" -and \( -not -path "./target/scala-*/mids-mumbler-assembly-*" -and -not -path "./messages/target*" \) -printf "%f\n" -exec cp {} $TMP \;

TARBALL_PATH=$TMP_ROOT/mids-mumbler-$VERSION.tar.gz
( cd $TMP_ROOT;
  chown -R 1000:1000 .;
  tar cvzf $TARBALL_PATH ./$VERSION;)

# TODO: do check here to create release only if necessary and overwrite files in it when wanted (i.e. for SNAPSHOTs)
$HERE/github_release_manager.sh -l $GH_USER -t $GH_TOKEN -o michaeldye -r mids-mumbler -d "$VERSION" -c create

$HERE/github_release_manager.sh -l $GH_USER -t $GH_TOKEN -o michaeldye -r mids-mumbler -d "$VERSION" -c upload $TARBALL_PATH
