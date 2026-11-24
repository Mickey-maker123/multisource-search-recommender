'use strict';
const el = id => document.getElementById(id);
let pageRequest = null;
let generation = 0;
function reset() {
  generation++;
  pageRequest = null;
  el('next').disabled = true;
  el('results').replaceChildren();
  el('analysis').replaceChildren();
}
function node(tag, text) {
  const element = document.createElement(tag);
  element.textContent = text;
  return element;
}
function filters() {
  const result = {};
  for (const field of ['category', 'status']) if (el(field).value.trim()) result[field] = el(field).value.trim();
  if (el('source').value.trim()) result.sources = [el('source').value.trim()];
  if (el('type').value) result.types = [el('type').value];
  for (const [id, field] of [['from', 'updatedFrom'], ['to', 'updatedTo']]) {
    if (el(id).value) result[field] = new Date(el(id).value).toISOString();
  }
  return result;
}
async function api(route, request) {
  const response = await fetch('/api/engine/v1/' + route, {
    method: 'POST', headers: {'Content-Type': 'application/json', Authorization: 'Bearer ' + el('token').value.trim()},
    body: JSON.stringify(route === 'source' ? request : {schemaVersion: '1.0', ...request})
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.code || 'HTTP ' + response.status);
  return result;
}
function button(text, action) {
  const result = node('button', text);
  result.onclick = action;
  return result;
}
function feedbackControls(item, article) {
  const status = node('p', '');
  const controls = node('div', '');
  async function send(action, undoOf) {
    const userId = el('feedback-user').value.trim();
    if (!userId) { status.textContent = '请先填写与查询令牌对应的反馈用户编号。'; return; }
    const current = generation;
    const eventId = crypto.randomUUID();
    for (const child of controls.children) child.disabled = true;
    try {
      const result = await api('events:batch', {events: [{schemaVersion: '1.0', eventId,
        domain: item.object.domain, source: item.object.source, userId, object: item.object,
        action, sourceAction: action, occurredAt: new Date().toISOString(), ...(undoOf ? {undoOf} : {})}]});
      if (current !== generation) return;
      const outcome = result.results[0];
      if (!['applied', 'duplicate'].includes(outcome.status)) throw new Error(outcome.code);
      status.textContent = action === 'undo' ? '已撤销；重新推荐查看结果。' : '已记录；重新推荐后生效。';
      controls.replaceChildren();
      if (action !== 'undo') controls.append(button('撤销本次反馈', () => send('undo', eventId)));
    } catch (error) {
      if (current === generation) status.textContent = error.message;
    } finally { for (const child of controls.children) child.disabled = false; }
  }
  controls.append(button('收藏', () => send('save')), button('不感兴趣', () => send('dismiss')));
  article.append(controls, status);
}
function sourceLocation(item, container) {
  const location = item.location || {};
  const uri = location.uri || '';
  try {
    const url = new URL(uri);
    if (url.protocol === 'https:' && !url.username && !url.password) {
      const link = node('a', '打开原始来源');
      link.href = url.href;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      container.append(link);
    }
  } catch (_) { /* Invalid source addresses remain plain text. */ }
  container.append(button('查看当前源对象与位置', () => openSource(item.object, item.location, item.version)));
  container.append(node('pre', JSON.stringify(location, null, 2)));
}
async function openSource(ref, anchor, expectedVersion) {
  const current = ++generation;
  try {
    const result = await api('source', ref);
    if (current !== generation) return;
    const changed = expectedVersion !== undefined && expectedVersion !== result.item.version;
    el('analysis').replaceChildren(node('h3', result.item.title || ref.nativeId),
      node('p', '当前版本：' + result.item.version + (changed ? '；资料已更新，展示当前正文，原命中位置需重新检索。' : '')));
    const loaded = await showSourceContent({schemaVersion: '1.1', object: ref, version: result.item.version, offset: 0, limit: 5,
      ...(!changed && anchor ? {anchor} : {})}, current);
    if (current !== generation) return;
    el('analysis').scrollIntoView({behavior: 'smooth', block: 'center'});
    el('notice').textContent = loaded ? '当前源对象已重新核验权限与版本。' : '当前正文读取未完成，请查看提示。';
  } catch (error) {
    if (current === generation) { el('analysis').replaceChildren(); el('notice').textContent = error.message; }
  }
}
async function showSourceContent(request, current) {
  try {
    const result = await api('source-content', request);
    if (current !== generation) return;
    const body = node('div', '');
    if (result.anchorMatched === false) body.append(node('p', '未定位到该命中位置，以下从当前正文起点展示。'));
    if (!result.parts.length) body.append(node('p', '当前无可读正文；解析状态：' + result.state + '，' + result.code));
    for (const part of result.parts) {
      body.append(node('p', `正文片段 ${part.index + 1} / ${result.totalParts}`),
        node('pre', JSON.stringify(part.location, null, 2)), node('pre', part.text));
    }
    if (result.hasMore) body.append(button('继续阅读正文', () => {
      body.remove();
      return showSourceContent({schemaVersion: '1.1', object: request.object, version: result.version,
        contentGuard: result.contentGuard, offset: result.nextOffset, limit: request.limit}, current);
    }));
    el('analysis').append(body);
    return true;
  } catch (error) {
    if (current !== generation) return;
    el('analysis').replaceChildren(node('p', error.message === 'SOURCE_CHANGED'
      ? '资料或权限已更新，请重新打开当前源对象。' : error.message));
    return false;
  }
}
async function analyze(template, item) {
  const current = ++generation;
  try {
    const result = await api('analyze', {template, filters: filters(), ...(template === 'group_by' ? {groupBy: el('group').value} : {}), ...(item ? {item} : {})});
    if (current !== generation) return;
    el('analysis').replaceChildren(node('pre', JSON.stringify(result, null, 2)));
    for (const relation of result.relations || []) el('analysis').append(button('查看关联对象 ' + relation.target.source + '/' + relation.target.nativeId, () => openSource(relation.target)));
    el('notice').textContent = '分析完成。value: 表示字段值，missing 表示缺失字段。';
  } catch (error) { if (current === generation) { el('analysis').replaceChildren(); el('notice').textContent = error.message; } }
}
async function retrieve(route, request) {
  const current = ++generation;
  el('next').disabled = true;
  el('notice').textContent = '正在查询…';
  try {
    const result = await api(route, request);
    if (current !== generation) return;
    pageRequest = result.hasMore ? {route, request: {...request, cursor: result.nextCursor}} : null;
    el('next').disabled = !pageRequest;
    el('results').replaceChildren();
    for (const hit of result.items) {
      const item = hit.item;
      const article = document.createElement('article');
      article.append(node('h3', item.title || item.object.nativeId), node('p', item.object.source + ' · ' + item.type), node('p', (item.text || '').slice(0, 700)), node('p', hit.reasons.join(' / ')));
      sourceLocation(item, article);
      if(item.typedFields)article.append(node('pre',JSON.stringify(item.typedFields,null,2)));
      article.append(button('显式关联', () => analyze('relations', item.object)), button('相关推荐', () => retrieve('recommendations', {kind: 'related', item: item.object, pageSize: 10, filters: filters()})));
      feedbackControls(item, article);
      el('results').append(article);
    }
    const feedback={incomplete:'检索未完整完成，请查看降级原因。',no_results:'当前可访问资料中没有匹配结果。',
      low_semantic_match:'语义匹配较弱，以下相关结果供核对，可能没有所需资料。',
      low_term_support:'当前返回片段未覆盖部分查询术语，可能缺少所需依据；保留结果供核对，不代表全库不存在。',
      keyword_results:'按关键词或精确条件匹配。',semantic_results:'已找到语义相关资料，请核对原文。'};
    el('notice').textContent = (feedback[result.matchStatus] || '') + ' 本页 ' + result.items.length + ' 项 · 候选上限 ' + result.candidateLimit + (result.degraded ? ' · 降级 ' + result.degradedReasons.join(', ') : '');
  } catch (error) {
    if (current !== generation) return;
    el('results').replaceChildren();
    pageRequest = null;
    el('notice').textContent = error.message;
  }
}
el('token').oninput = reset;
el('clear').onclick = () => { el('token').value = ''; el('feedback-user').value = ''; reset(); el('notice').textContent = '身份与结果已清除。'; };
el('search').onclick = () => {
  const selected=filters();const field=el('exact-field').value.trim();
  if(field) {
    const type=el('exact-type').value;let value=el('exact-value').value;
    if(type==='boolean') {
      if(!['true','false'].includes(value)){el('notice').textContent='布尔条件请输入 true 或 false。';return;}
      value=value==='true';
    }
    selected.typed=[{field,type,op:el('exact-op').value,value}];
  }
  retrieve('search',{schemaVersion:'1.1',query:el('query').value,mode:field&&!el('query').value.trim()?'keyword':el('mode').value,filters:selected,pageSize:10});
};
el('personal').onclick = () => {
  const request = {kind: 'personal', pageSize: 10, filters: filters()};
  if (el('task').value.trim()) request.task = {taskId: 'demo-task', text: el('task').value.trim(), expiresAt: new Date(Date.now() + 3600000).toISOString()};
  retrieve('recommendations', request);
};
el('next').onclick = () => { if (pageRequest) retrieve(pageRequest.route, pageRequest.request); };
el('count').onclick = () => analyze('count');
el('groupBy').onclick = () => analyze('group_by');

let runtimeGeneration = 0;
let importJob = null;
const sourceJobs = new Map();
function clearRuntime() {
  runtimeGeneration++; el('runtime').replaceChildren(); el('import-job').replaceChildren();
  el('import-preview-result').replaceChildren();el('import-path').value='';
  el('import-source').replaceChildren(); el('import-source').disabled = true;
  el('import-start').disabled = true; el('import-refresh').disabled = true;
  importJob = null; sourceJobs.clear();
}
el('admin-token').oninput = clearRuntime;
el('runtime-clear').onclick = () => { el('admin-token').value = ''; clearRuntime(); };
el('runtime-refresh').onclick = async () => {
  const current = ++runtimeGeneration;
  el('runtime').replaceChildren(node('p', '正在读取运行状态…'));
  try {
    const response = await fetch('/api/engine/admin/v1/status', {
      headers: {Authorization: 'Bearer ' + el('admin-token').value.trim()}, cache: 'no-store'
    });
    const status = await response.json();
    if (current !== runtimeGeneration) return;
    if (!response.ok) throw new Error(status.code || 'HTTP ' + response.status);
    const names = {available: '可用', unavailable: '不可用', not_configured: '未配置'};
    const rows = [node('p', '采样时间：' + status.observedAt),
      node('p', '存储：' + (names[status.store.state] || status.store.state)),
      node('p', '搜索：' + (names[status.search.state] || status.search.state))];
    if (status.store.items) rows.push(node('p', '对象状态：' + JSON.stringify(status.store.items)),
      node('p', '解析任务：' + JSON.stringify(status.store.jobs)), node('p', '导入任务：' + JSON.stringify(status.store.import_runs)));
    if (status.store.directory_runs) rows.push(node('p', '在线目录任务：' + JSON.stringify(status.store.directory_runs)));
    if (status.search.readyObjects !== undefined) rows.push(node('p',
      `当前可索引对象 ${status.search.readyObjects}；关键词已同步 ${status.search.keywordObjects}；向量已同步 ${status.search.vectorObjects}`));
    const model = status.search.model;
    if (model) rows.push(node('p', '模型：' + (names[model.state] || model.state)));
    if (model && model.queue) rows.push(node('p',
      `推理执行中：${model.queue.active ? '是' : '否'}；等待查询 ${model.queue.waiting.query}；等待导入 ${model.queue.waiting.index}；等待容量 ${model.queue.capacity}`),
      node('p', `累计调度单元：完成 ${model.queue.completed}；失败 ${model.queue.failed}；拒绝 ${model.queue.rejected}；排队超时 ${model.queue.expired}；取消 ${model.queue.cancelled}`));
    el('runtime').replaceChildren(...rows);
  } catch (error) {
    if (current === runtimeGeneration) el('runtime').replaceChildren(node('p', error.message));
  }
};

async function adminApi(route, body) {
  const response = await fetch('/api/engine/admin/v1/' + route, {
    method: body ? 'POST' : 'GET', cache: 'no-store',
    headers: {Authorization: 'Bearer ' + el('admin-token').value.trim(), ...(body ? {'Content-Type': 'application/json'} : {})},
    ...(body ? {body: JSON.stringify({schemaVersion: '1.0', ...body})} : {})
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.code || 'HTTP ' + response.status);
  return result;
}
el('import-load').onclick = async () => {
  const current = ++runtimeGeneration;
  try {
    const result = await adminApi('import-sources');
    if (current !== runtimeGeneration) return;
    el('import-source').replaceChildren(); sourceJobs.clear();
    for (const source of result.sources) {
      const option = node('option', source.label + ' · ' + source.sourceId);
      option.value = source.sourceId; el('import-source').append(option);
      sourceJobs.set(source.sourceId, source.lastRunId || null);
    }
    el('import-source').disabled = !result.sources.length;
    el('import-start').disabled = !result.sources.length;
    importJob = sourceJobs.get(el('import-source').value) || null;
    el('import-refresh').disabled = !importJob;
    el('import-job').replaceChildren(node('p', result.sources.length ? '已读取批准来源，可导入或查看最近任务。' : '尚未配置目录来源。'));
  } catch (error) { if (current === runtimeGeneration) el('import-job').replaceChildren(node('p', error.message)); }
};
el('import-source').onchange = () => {
  el('import-preview-result').replaceChildren();
  runtimeGeneration++; importJob = sourceJobs.get(el('import-source').value) || null;
  el('import-refresh').disabled = !importJob; el('import-job').replaceChildren();
};
async function showImport(id, offset = 0) {
  const current = ++runtimeGeneration;
  try {
    const job = await adminApi('imports/' + encodeURIComponent(id) + (offset ? '?offset=' + offset : ''));
    if (current !== runtimeGeneration) return;
    const states = {queued: '排队中', running: '运行中', completed: '完成', completed_with_errors: '完成，有失败项', failed: '失败', ready: '已解析', unchanged: '未变化', rejected: '拒绝', needs_ocr: '需要OCR', deleted: '已同步删除'};
    const rows = [node('h4', states[job.state] || job.state), node('p', `已处理记录 ${job.processed}；扫描文件 ${job.total}；错误 ${job.errors}。处理记录包含缺失文件同步。`),
      node('p', `扫描${job.scanComplete ? '完整' : '未完成'} · ${job.code} · 任务 ${job.runId}`)];
    for (const entry of job.entries) {
      const article = node('article', '');
      article.append(node('p', entry.path + '：' + (states[entry.state] || entry.state) + ' · ' + entry.code));
      if (entry.objectKey && ['ready', 'unchanged'].includes(entry.state)) {
        const [domain, source, nativeId] = entry.objectKey.split(':');
        article.append(button('阅读已导入正文', () => openSource({domain, source, nativeId})));
      }
      rows.push(article);
    }
    if (job.nextOffset !== undefined) rows.push(button('下一批处理记录', () => showImport(id, job.nextOffset)));
    if (['failed', 'completed_with_errors'].includes(job.state)) rows.push(button('重试此来源', () => startImport(id)));
    el('import-job').replaceChildren(...rows);
  } catch (error) { if (current === runtimeGeneration) el('import-job').replaceChildren(node('p', error.message)); }
}
async function startImport(previous) {
  const current = ++runtimeGeneration;
  try {
    const result = await adminApi(previous ? 'imports/' + encodeURIComponent(previous) + '/retry' : 'imports', previous ? {} : {sourceId: el('import-source').value});
    if (current !== runtimeGeneration) return;
    importJob = result.runId; sourceJobs.set(el('import-source').value, importJob);
    el('import-refresh').disabled = false; await showImport(importJob);
  } catch (error) { if (current === runtimeGeneration) el('import-job').replaceChildren(node('p', error.message)); }
}
el('import-start').onclick = () => startImport();
el('import-refresh').onclick = () => { if (importJob) return showImport(importJob); };
el('import-preview').onclick=async()=>{
  const current=++runtimeGeneration;
  try {
    const result=await adminApi('import-preview',{schemaVersion:'1.1',sourceId:el('import-source').value,relativePath:el('import-path').value});
    if(current!==runtimeGeneration)return;
    const rows=[node('p',`共 ${result.total} 行，错误 ${result.errors} 行；预览前 ${result.previewLimit} 行，不写入资料库。`)];
    for(const row of result.rows)rows.push(node('pre',JSON.stringify(row,null,2)));
    el('import-preview-result').replaceChildren(...rows);
  }catch(error){if(current===runtimeGeneration)el('import-preview-result').replaceChildren(node('p',error.message));}
};
