"""Authenticated local HTTP + MCP endpoint. Never expose plain HTTP publicly."""
import contextlib
import hmac
import os
from pathlib import Path
from mcp.server.fastmcp import FastMCP, Context
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route, Mount
from starlette.middleware.base import BaseHTTPMiddleware
from store import Bridge

owner = os.environ.get('LIANXU_OWNER_TOKEN', '')
if len(owner) < 32:
    raise RuntimeError('LIANXU_OWNER_TOKEN must be at least 32 characters')
bridge = Bridge(os.environ.get('LIANXU_BRIDGE_DB', str(Path.home() / '.lianxu-bridge.sqlite3')))
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
    """Submit text cards: proposal={boardId,boardRevision,cards:[{id,title,text,span}],inputIds:[...]}. IDs start with boardId + ':external:'. Span is 2, 3 or 6. Use the snapshot board revision. Phone validates and may defer while editing; pending does not mean applied."""
    return bridge.propose(agent_epoch(ctx), proposal_id, base_revision, proposal)

async def phone(request):
    header = request.headers.get('authorization', '')
    if not header.startswith('Bearer ') or not hmac.compare_digest(header[7:], owner):
        return JSONResponse({'error':'unauthorized'}, status_code=401)
    try:
        if int(request.headers.get('content-length', '0')) > 2_000_000: return JSONResponse({'error':'too large'}, status_code=413)
        data = await request.json()
        action = request.path_params['action']
        if action == 'switch': bridge.switch(data['epoch'], data['mode'], data.get('agent'), data.get('agentToken'))
        elif action == 'control':
            with bridge.lock:
                row = bridge.db.execute('SELECT epoch,mode,agent,accepted FROM control WHERE id=1').fetchone()
                return JSONResponse(dict(row))
        elif action == 'snapshot': return JSONResponse({'revision':bridge.snapshot(data['epoch'], data['body'])})
        elif action == 'enqueue': bridge.enqueue(data['id'], data['body'], data['epoch'])
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
