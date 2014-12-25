#!/bin/bash
#
# creates the python classes for our .proto
#


#project_base="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

project_base="/Users/vineetbhatkoti/Desktop/CMPE275/core-netty-4.2"
#protoc=${project_base}/resources/comm.proto
PROTOC_HOME=/Users/vineetbhatkoti/Downloads/protobuf-2.6.0
#rm ${project_base}/python/comm_pb2.py

#protoc protoc="/usr/local/bin/protoc" -I=${project_base}/resources --python_out=${project_base}/      --proto_path=${project_base}/resources/comm.proto
$PROTOC_HOME/src/protoc --proto_path=${project_base}/resources --python_out=${project_base}/generated ${project_base}/resources/comm.proto
