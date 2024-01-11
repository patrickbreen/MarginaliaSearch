#!/bin/bash

#
# This script will set up a Marginalia instance in a given directory.
# It will create a docker-compose.yml file, and a directory structure
# with the necessary files.  It will also create a MariaDB database
# in docker, and run the flyway migrations to set up the database.
#
# After the script is run, the instance can be started with
# $ docker-compose up -d
#
# The instance can be stopped with
# $ docker-compose down -v
#
# It is likely that you will want to edit the docker-compose.yml file
# to change the ports that the services are exposed on, and to change
# the volumes that are mounted.  The default configuration is provided
# a starting point.

set -e

if ! command -v envsubst &> /dev/null
then
    echo "The envsubst command could not be found, please install it.  It is usually part of GNU gettext."
    exit
fi

if [ -z "${1}" ]; then
  echo "Usage: $0 <install directory>"
  exit 1
fi
if [ -e "${1}" ]; then
  echo "ERROR: Destination ${1} already exists, refusing to overwrite"
  exit 1
fi

INSTALL_DIR=${1}

echo "We're going to set up a Mariadb database in docker, please enter some details"

read -p "MariaDB user (e.g. marginalia): " MARIADB_USER
read -s -p "MariaDB password (e.g. hunter2 ;-): " MARIADB_PASSWORD
echo
read -s -p "MariaDB password (again): " MARIADB_PASSWORD2
echo

export MARIADB_USER
export MARIADB_PASSWORD

if [ "${MARIADB_PASSWORD}" != "${MARIADB_PASSWORD2}" ]; then
  echo "ERROR: Passwords do not match"
  exit 1
fi

echo "Will install to ${INSTALL_DIR}"
read -p "Press enter to continue, or Ctrl-C to abort"

pushd $(dirname $0)

./setup.sh ## Ensure that the setup script has been run

mkdir -p ${INSTALL_DIR}

echo "** Copying files to ${INSTALL_DIR}"

for dir in model data conf env; do
  if [ ! -d ${dir} ]; then
    echo "ERROR: ${dir} does not exist"
    exit 1
  fi
  echo "Copying ${dir}/"
  mkdir -p ${INSTALL_DIR}/${dir}
  find  ${dir} -maxdepth 1 -type f -exec cp -v {} ${INSTALL_DIR}/{} \;
done

echo "** Copying settings files"
cp prometheus.yml ${INSTALL_DIR}/

echo "** Creating directories"
mkdir -p ${INSTALL_DIR}/logs
mkdir -p ${INSTALL_DIR}/db
mkdir -p ${INSTALL_DIR}/index-1/{work,index,backup,storage,uploads}

echo "** Updating settings files"

envsubst < install/mariadb.env.template > ${INSTALL_DIR}/env/mariadb.env
envsubst < install/db.properties.template > ${INSTALL_DIR}/conf/db.properties

echo "** Creating docker-compose.yml"

## Hack to get around envstubst substituting these values, which we want to be verbatim
export uval="\$\$MARIADB_USER"
export pval="\$\$MARIADB_PASSWORD"

export INSTALL_DIR
envsubst < install/docker-compose.yml.template >${INSTALL_DIR}/docker-compose.yml

popd