#!/bin/bash
R=/home/user/flottila; L=$R/agent/work/FIX-268-stack.log; T=$(mktemp -d)
export RAIL8_ADMIN_TOKEN=$(openssl rand -hex 16) RAIL8_S3_SECRET_KEY=$(openssl rand -hex 16)
cd $R/deploy/compose
: > $L
say(){ echo "$*" >> $L; }
sh_(){ say "\$ $1"; bash -c "$1" >> $L 2>&1; say "[exit $?]"; }
api(){ # method path [data-file] ; prints status & body
  local m=$1 p=$2 f=$3 port=${4:-8081}
  say "\$ curl --noproxy '*' -i -X $m -H 'Authorization: Bearer ***' localhost:$port$p ${f:+--data-binary @$f}"
  curl --noproxy '*' -s -i -X $m -H "Authorization: Bearer $RAIL8_ADMIN_TOKEN" ${f:+--data-binary @$f} localhost:$port$p > $T/out 2>&1
  cat $T/out >> $L; say; }
raw(){ local m=$1 port=$2 p=$3 f=$4
  say "\$ curl --noproxy '*' -i -X $m localhost:$port$p --data-binary @$f"
  curl --noproxy '*' -s -i -X $m --data-binary @$f localhost:$port$p > $T/out 2>&1; cat $T/out >> $L; say; }
poll(){ # desc path needle  -> polls admin, max 60s
  local s=0; while [ $s -lt 60 ]; do
    curl --noproxy '*' -s -H "Authorization: Bearer $RAIL8_ADMIN_TOKEN" localhost:8081$2 > $T/p 2>&1
    if eval "$3"; then break; fi; sleep 1; s=$((s+1)); done
  say "[poll $1: $s s, $( [ $s -lt 60 ] && echo satisfied || echo TIMEOUT )]"; }
last_ok(){ python3 -I -c 'import json,sys;d=json.load(open(sys.argv[1]));s=d["steps"];sys.exit(0 if s[-1]["outcome"]=="OK" and s[-1]["attempt"]>=int(sys.argv[2]) and s[-1]["name"]=="partner" else 1)' $1 $2 2>/dev/null; }
mkfix(){ sed "s#<Id>4711</Id>#<Id>$1</Id>#" $R/examples/demo/fixtures/sample-invoice.xml > $T/$1.xml; }
say "## A.1"; say "commit $(git rev-parse HEAD)"
sh_ "docker compose up -d --build"
poll "A.1 deploy exited" /health '[ "$(docker compose ps -a deploy --format "{{.State}} {{.ExitCode}}")" = "exited 0" ]'
sh_ "docker compose ps -a"
api GET /health
say "## A.2"
raw POST 8080 /in $R/examples/demo/fixtures/sample-invoice.xml
raw POST 8082 /invoice/async $R/examples/demo/fixtures/sample-invoice.xml
ID=$(grep -i '^x-message-id' $T/out | tr -d '\r' | awk '{print $2}'); say "id=$ID"
poll "A.2 timeline" /admin/messages/$ID 'last_ok $T/p 1'
api GET /admin/messages/$ID
say "## A.3"
sh_ "docker compose stop echo"
mkfix 4801; raw POST 8082 /invoice/async $T/4801.xml
M1=$(grep -i '^x-message-id' $T/out | tr -d '\r' | awk '{print $2}'); say "M1=$M1"
poll "A.3 dlq" /admin/dlq 'grep -q "$M1" $T/p'
api GET /admin/dlq
api GET /admin/messages/$M1
say "## A.4"
sh_ "docker compose start echo"
poll "A.4 echo healthy" /health 'docker compose ps echo | grep -q healthy'
sh_ "docker compose ps echo"
api POST /admin/dlq/$M1/retry
poll "A.4 timeline" /admin/messages/$M1 'last_ok $T/p 3'
api GET /admin/messages/$M1
api GET /admin/dlq
api POST /admin/dlq/$M1/retry
say "## A.5"
sh_ "docker compose stop echo"
pid=$(docker inspect -f '{{.State.Pid}}' "$(docker compose ps -q netns)"); say "netns pid=$pid"
PL=$T/partner.log; FL=$T/flag
sudo -n nsenter -t "$pid" -n python3 $R/agent/work/FIX-268-partner.py 8090 $PL $FL >> $L 2>&1 &
PP=$!; sleep 2
mkfix 4802; raw POST 8082 /invoice/async $T/4802.xml
M2=$(grep -i '^x-message-id' $T/out | tr -d '\r' | awk '{print $2}'); say "M2=$M2"
poll "A.5 dlq" /admin/dlq 'grep -q "$M2" $T/p'
sh_ "cat $PL"
touch $FL
api POST /admin/dlq/$M2/retry
poll "A.5 timeline" /admin/messages/$M2 'last_ok $T/p 3'
api GET /admin/messages/$M2
sh_ "cat $PL"
say "sends=$(wc -l < $PL) 503=$(grep -c -- '-> 503' $PL) 200=$(grep -c -- '-> 200' $PL)"
say "distinct x-message-id: $(grep -o 'x-message-id=[^ ]*' $PL | sort -u | tr '\n' ' ')"
sudo -n pkill -f FIX-268-partner.py; kill $PP 2>/dev/null
sh_ "docker compose start echo"
say "## A.6"
docker compose logs rail8 > $R/agent/work/FIX-268-node.log 2>&1
say "ERROR lines: $(grep -c ERROR $R/agent/work/FIX-268-node.log)"
grep ERROR $R/agent/work/FIX-268-node.log | sed 's/^[^|]*| *//' | cut -c1-300 | sort | uniq -c >> $L
sh_ "docker compose --profile history --profile kafka down -v"
sh_ "docker volume ls"
rm -rf $T
