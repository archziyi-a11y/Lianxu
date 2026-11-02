"""Authenticated local HTTP + MCP endpoint. Never expose plain HTTP publicly."""
import contextlib
import hmac
import os
import json
import urllib.request
import asyncio
import threading
from urllib.parse import urlparse
from pathlib import Path
from mcp.server.fastmcp import FastMCP, Context
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route, Mount
from starlette.middleware.base import BaseHTTPMiddleware
from store import Bridge
from mcp.types import ImageContent

owner = os.environ.get('LIANXU_OWNER_TOKEN', '')
if len(owner) < 32:
    raise RuntimeError('LIANXU_OWNER_TOKEN must be at least 32 characters')
bridge = Bridge(os.environ.get('LIANXU_BRIDGE_DB', str(Path.home() / '.lianxu-bridge.sqlite3')))
webhook_url = os.environ.get('LIANXU_GROK_WEBHOOK_URL', '')
webhook_token = os.environ.get('LIANXU_GROK_WEBHOOK_TOKEN', '')
trigger_lock = threading.Lock()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl): return None
if bool(webhook_url) != bool(webhook_token) or webhook_url and urlparse(webhook_url).scheme != 'https':
    raise RuntimeError('Grok Bot webhook requires both an HTTPS URL and a token')

def trigger_input(id, epoch):
    receipt_id = f'{epoch}:{id}'
    with trigger_lock:
        if not webhook_url or bridge.webhook_sent(receipt_id): return
        body = json.dumps({'event':'lianxu_input', 'input_id':id}, separators=(',', ':')).encode()
        request = urllib.request.Request(webhook_url, data=body, method='POST', headers={'Authorization':'Bearer '+webhook_token, 'Content-Type':'application/json'})
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=10) as response:
            if response.status != 200: raise RuntimeError('Grok Bot webhook did not accept the run')
        bridge.mark_webhook_sent(receipt_id)
mcp = FastMCP('Lianxu', host='127.0.0.1', streamable_http_path='/', stateless_http=True, json_response=True)

def agent_epoch(ctx: Context):
    request = ctx.request_context.request
    header = request.headers.get('authorization', '') if request else ''
    if not header.startswith('Bearer '): raise PermissionError('authorization required')
    return bridge.authenticate(header[7:])

@mcp.tool()
def read_handoff(ctx: Context) -> dict:
    """Read latest phone snapshot and pending inputs prior to accepting handoff."""
    return bridge.read_handoff(agent_epoch(ctx))

@mcp.tool()
def accept_handoff(snapshot_revision: int, ctx: Context) -> str:
    """Accept responsibility after reviewing the given current snapshot revision."""
    bridge.accept(agent_epoch(ctx), snapshot_revision)
    return 'accepted'

@mcp.tool()
def read_pending(ctx: Context) -> list:
    """Read pending input; completion must be confirmed by the phone."""
    return bridge.pending(agent_epoch(ctx))

@mcp.tool()
def submit_proposal(proposal_id: str, base_revision: int, proposal: dict, ctx: Context) -> str:
    """Submit today layout: proposal={boardId,boardRevision,cards:[{id,title,span,group?,emphasis?,collapsed?,followUpId?,components}],inputIds,removeIds?,order?}. Card ids start with boardId+':external:'. Span 2/3/6. Components: text {type,text}; input {type,kind,fields,suggestedName?,actions?}; metric {type,calculationId,path,label,unit}; trend {type,calculationId,label,unit}; choice {type,options:[{key,label}]}. Actions are user-triggered {kind:'start_timer',label,seconds} or training input {kind:'record_set',label,planId?}. Never supply actual values as suggestions. A follow-up must reference its active followUpId; never rephrase a cancelled follow-up. The phone validates data and may defer during editing; pending does not mean applied."""
    return bridge.propose(agent_epoch(ctx), proposal_id, base_revision, proposal)

@mcp.tool()
def submit_requirement(proposal_id: str, base_revision: int, merge_key: str, scenario: str, limitation: str, workaround: str, expected_interaction: str, input_ids: list[str], ctx: Context) -> str:
    """Request an app capability. Reuse the same merge_key for the same need. This records a request only; it does not change code or send messages."""
    body = {'type':'requirement', 'mergeKey':merge_key, 'scenario':scenario, 'limitation':limitation,
            'workaround':workaround, 'expectedInteraction':expected_interaction, 'inputIds':input_ids}
    return bridge.propose(agent_epoch(ctx), proposal_id, base_revision, body)

@mcp.tool()
def submit_plan(proposal_id: str, base_revision: int, plan_id: str, goal_id: str, goal_revision: int, content: str, reason: str, effective_at: int, activate: bool, changes_goal: bool, breaks_constraints: bool, input_ids: list[str], ctx: Context) -> str:
    """Submit a version under the existing goal and user constraints. Never change a goal or exceed a constraint without the user's decision. effective_at is Unix milliseconds; future plans cannot be activated early."""
    body = {'type':'plan', 'planId':plan_id, 'goalId':goal_id, 'goalRevision':goal_revision, 'content':content,
            'reason':reason, 'effectiveAt':effective_at, 'activate':activate, 'changesGoal':changes_goal, 'breaksConstraints':breaks_constraints, 'inputIds':input_ids}
    return bridge.propose(agent_epoch(ctx), proposal_id, base_revision, body)

@mcp.tool()
def submit_archive(proposal_id: str, base_revision: int, cards: list[dict], input_ids: list[str], ctx: Context) -> str:
    """Arrange archive topics: each card has title, span (2/3/6), optional group, emphasis, collapsed, and blocks [{topicKey,type,path?,label?,text?}]; block type is metric, trend, or text. Metric paths refer to the topic's calculation. Archived and pinned topics are protected by the phone."""
    return bridge.propose(agent_epoch(ctx), proposal_id, base_revision, {'type':'archive','cards':cards,'inputIds':input_ids})

@mcp.tool()
def read_proposal_status(proposal_id: str, ctx: Context) -> str:
    """Check proposal status: pending may include waiting for the phone or an edit to finish; other statuses include applied, rejected and decision."""
    return bridge.proposal_status(agent_epoch(ctx), proposal_id)

@mcp.tool()
def request_attachment(attachment_id: str, ctx: Context) -> str:
    """Request an image referenced by a pending input. The phone uploads it during the next sync."""
    return bridge.request_image(agent_epoch(ctx), attachment_id)

@mcp.tool()
def read_attachment(attachment_id: str, ctx: Context) -> list[ImageContent]:
    """Read a previously requested image once ready. Images are discarded from bridge memory on handoff."""
    mime, encoded = bridge.read_image(agent_epoch(ctx), attachment_id)
    return [ImageContent(type='image', mimeType=mime, data=encoded)]

async def phone(request):
    header = request.headers.get('authorization', '')
    if not header.startswith('Bearer ') or not hmac.compare_digest(header[7:], owner):
        return JSONResponse({'error':'unauthorized'}, status_code=401)
    try:
        action = request.path_params['action']
        limit = 36_000_000 if action == 'image' else 16_000_000
        if int(request.headers.get('content-length', '0')) > limit: return JSONResponse({'error':'too large'}, status_code=413)
        if len(await request.body()) > limit: return JSONResponse({'error':'too large'}, status_code=413)
        data = await request.json()
        if action == 'switch': bridge.switch(data['epoch'], data['mode'], data.get('agent'), data.get('agentToken'))
        elif action == 'control':
            with bridge.lock:
                row = bridge.db.execute('SELECT epoch,mode,agent,accepted FROM control WHERE id=1').fetchone()
                return JSONResponse(dict(row) | {'triggerConfigured': bool(webhook_url)})
        elif action == 'image_requests': return JSONResponse({'ids':bridge.requested_images(data['epoch'])})
        elif action == 'image':
            bridge.upload_image(data['epoch'], data['id'], data['mime'], data['sha256'], data['data'])
            try: await asyncio.to_thread(trigger_input, 'image:'+data['id'], data['epoch'])
            except Exception: return JSONResponse({'error':'trigger unavailable'}, status_code=503)
        elif action == 'snapshot': return JSONResponse({'revision':bridge.snapshot(data['epoch'], data['body'])})
        elif action == 'enqueue':
            bridge.enqueue(data['id'], data['body'], data['epoch'])
            try: await asyncio.to_thread(trigger_input, data['id'], data['epoch'])
            except Exception: return JSONResponse({'error':'trigger unavailable'}, status_code=503)
        elif action == 'proposals': return JSONResponse({'proposals':bridge.proposals(data['epoch'])})
        elif action == 'ack': bridge.acknowledge(data['epoch'], data['id'], data['status'])
        else: return JSONResponse({'error':'not found'}, status_code=404)
        return JSONResponse({'ok':True})
    except (KeyError, ValueError, PermissionError, TypeError) as exc:
        return JSONResponse({'error':str(exc)}, status_code=409)

@contextlib.asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run(): yield

class AgentAuthentication(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        if request.url.path.startswith('/mcp'):
            header = request.headers.get('authorization', '')
            try:
                if not header.startswith('Bearer '): raise PermissionError('authorization required')
                bridge.authenticate(header[7:])
            except PermissionError:
                return JSONResponse({'error':'unauthorized'}, status_code=401)
        return await call_next(request)

app = Starlette(routes=[Route('/phone/{action}', phone, methods=['POST']), Mount('/mcp', app=mcp.streamable_http_app())], lifespan=lifespan)
app.add_middleware(AgentAuthentication)

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=int(os.environ.get('LIANXU_BRIDGE_PORT', '8765')), access_log=False)
