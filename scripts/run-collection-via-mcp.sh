#!/usr/bin/env bash
#
# Trigger the ollama-mcp-server's `run_postman_collection` tool over MCP (JSON-RPC),
# WITHOUT Postman's AI / Agent Mode — so it uses no AI credits.
#
# It performs the MCP handshake (initialize -> notifications/initialized) then calls the
# tool, and prints a Runner-style breakdown (folder > request, status, time, PASS/FAIL).
#
# With ANALYZE=1 it asks the tool to also analyse the run with the local Ollama model
# (the tool's built-in `analyze` argument — a single MCP call does run + analysis). The
# deterministic breakdown is the source of truth; the analysis is advisory.
#
# Usage:
#   scripts/run-collection-via-mcp.sh [collectionPath]
#   ANALYZE=1 scripts/run-collection-via-mcp.sh
#
# Env:
#   MCP_URL         MCP endpoint (default http://localhost:8085/mcp)
#   ANALYZE         set to 1 to also get an Ollama analysis of the result (default off)
#   ANALYZE_MODEL   model used for the analysis (default qwen3:8b — small/fast)
set -uo pipefail

MCP_URL="${MCP_URL:-http://localhost:8085/mcp}"
COLLECTION="${1:-}"
PV="2025-06-18"
H_JSON='Content-Type: application/json'
H_ACCEPT='Accept: application/json, text/event-stream'

hdr="$(mktemp)"; resp="$(mktemp)"
cleanup() { rm -f "$hdr" "$resp"; }
trap cleanup EXIT

# 1) initialize -> capture the Mcp-Session-Id every later call needs.
curl -sS -D "$hdr" -o /dev/null -H "$H_JSON" -H "$H_ACCEPT" -X POST "$MCP_URL" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"'"$PV"'","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}'
SID="$(grep -i '^mcp-session-id:' "$hdr" | awk '{print $2}' | tr -d '\r')"
if [ -z "$SID" ]; then
  echo "ERROR: no Mcp-Session-Id returned. Is the server running at $MCP_URL ?" >&2
  exit 1
fi

# 2) notifications/initialized (the server acks with 202).
curl -sS -o /dev/null -H "$H_JSON" -H "$H_ACCEPT" -H "Mcp-Session-Id: $SID" \
  -H "MCP-Protocol-Version: $PV" -X POST "$MCP_URL" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# Build the tool arguments (collectionPath optional; analyze/analysisModel when ANALYZE=1).
ARGS="$(ANALYZE="${ANALYZE:-0}" ANALYZE_MODEL="${ANALYZE_MODEL:-qwen3:8b}" node -e '
  const a = {};
  if (process.argv[1]) a.collectionPath = process.argv[1];
  if (process.env.ANALYZE === "1") { a.analyze = true; a.analysisModel = process.env.ANALYZE_MODEL; }
  process.stdout.write(JSON.stringify(a));
' "$COLLECTION")"

# 3) tools/call run_postman_collection (one call does run + optional analysis).
NOTE=""; [ "${ANALYZE:-0}" = "1" ] && NOTE=" (with analysis, model ${ANALYZE_MODEL:-qwen3:8b})"
echo "→ running collection via MCP at $MCP_URL$NOTE ..."
curl -sS --max-time 900 -H "$H_JSON" -H "$H_ACCEPT" -H "Mcp-Session-Id: $SID" \
  -H "MCP-Protocol-Version: $PV" -X POST "$MCP_URL" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_postman_collection","arguments":'"$ARGS"'}}' \
  -o "$resp"

# Print the Runner-style breakdown, then the analysis (if requested). Exit non-zero on failure.
node -e '
const fs=require("fs");
let t=fs.readFileSync(process.argv[1],"utf8").trim();
if(t[0]!=="{" && t.includes("data:")){
  t=t.split(/\r?\n/).filter(l=>l.startsWith("data:")).map(l=>l.slice(5).trim()).join("");
}
const env=JSON.parse(t);
if(env.error){console.error("MCP error:",JSON.stringify(env.error));process.exit(1);}
const o=JSON.parse(env.result.content[0].text);
if(o.error){console.error("Tool error:",o.error,o.detail||"");process.exit(1);}
console.log(`\n${o.passed?"✅ PASSED":"❌ FAILED"}  exitCode=${o.exitCode}  duration=${o.durationMs??"?"}ms`);
if(o.stats){console.log(`requests ${o.stats.requests.total} (failed ${o.stats.requests.failed}) · `
  +`assertions ${o.stats.assertions.total} (failed ${o.stats.assertions.failed})`);}
let lf="";
(o.requests||[]).forEach(r=>{
  if(r.folder!==lf){console.log("\n📁 "+(r.folder||"(root)"));lf=r.folder;}
  console.log(`  ${r.method} ${r.name}  →  ${r.code} ${r.status} · ${r.timeMs}ms · ${r.sizeB}B`);
  (r.assertions||[]).forEach(a=>console.log(`      [${a.result}] ${a.name}${a.error?" — "+a.error:""}`));
});
if(o.reportPath)console.log(`\nFull JSON report: ${o.reportPath}`);
if(o.analysis){console.log("\n🧠 Ollama analysis:\n"+o.analysis);}
else if(o.analysisError){console.error("\n(analysis error) "+o.analysisError);}
process.exit(o.passed?0:2);
' "$resp"
