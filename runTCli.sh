# !/usr/bin/sh
declare -i totSessions
declare -i repeatCnt
declare -i numUsers
case $# in
1)  numUsers=$1 ; totSessions=650000 ; numDmps=0 ; dumpWait=0 ; dumpIntvl=0 ;;
2)  numUsers=$1 ; totSessions=$2 ; numDmps=0 ; dumpWait=0 ; dumpIntvl=0 ;;
3) numUsers=$1 ; totSessions=$2 ; numDmps=$3 ; dumpWait=2 ; dumpIntvl=1 ;;
4) numUsers=$1 ; totSessions=$2 ; numDmps=$3 ; dumpWait=$4 ; dumpIntvl=1 ;;
5) numUsers=$1 ; totSessions=$2 ; numDmps=$3 ; dumpWait=$4 ; dumpIntvl=$5 ;;
*)  echo "Args are numUsers and optionally total # sessions  and optionally numDumps, dumpWait, and dumpIntvl for the run (total all users) which defaults to 650000" ; exit ;;
esac
repeatCnt=${totSessions}/${numUsers}
sed 's/xxxRepeatCnt/'${repeatCnt}'/' fb.mikeProps >fb.runProps
echo java -cp swAuto/lib/rsrchUtils.jar:swAuto/lib/http/commons-logging-1.1.3.jar:swAuto/lib/http/httpclient-4.3.3.jar:swAuto/lib/http/httpcore-4.3.2.jar:swAuto/lib/http/commons-codec-1.6.jar com/ibm/mike/samples/HttpClientDriver fb.runProps ${numUsers} ${numDmps} ${dumpWait} ${dumpIntvl}
java -cp swAuto/lib/rsrchUtils.jar:swAuto/lib/http/commons-logging-1.1.3.jar:swAuto/lib/http/httpclient-4.3.3.jar:swAuto/lib/http/httpcore-4.3.2.jar:swAuto/lib/http/commons-codec-1.6.jar com/ibm/mike/samples/HttpClientDriver fb.runProps ${numUsers} ${numDmps} ${dumpWait} ${dumpIntvl} &
