/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * ifmap 管理端可视化页面：原生 JS（ES5 语法 + Promise/fetch），零依赖、零构建。
 *
 * 两条硬约束：
 *   1) 不硬编码路径前缀。前缀可配（ifmap.admin.base-path），所以从当前页面 URL 反推 BASE；
 *      一旦有人写死 "/ifmap/admin"，改前缀的部署就整页 404 —— 有测试盯着这条（IfmapAdminUiScriptTest）。
 *   2) 所有写操作都带 X-Operator-Id / X-Request-Id（落审计字段），操作人由页面顶部输入。
 */
(function () {
  'use strict';

  var BASE = location.pathname.replace(/\/ui(\/.*)?$/, '');
  var META = { rules: [], actions: [], strategies: {}, enums: {} };
  var state = { rows: [], total: 0, page: 1, size: 20, current: null, branchCurrent: null };

  // ---------------------------------------------------------------- 基础工具

  function el(id) {
    return document.getElementById(id);
  }

  function esc(value) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function text(value) {
    return value === null || value === undefined ? '' : String(value);
  }

  function json(value) {
    return JSON.stringify(value, null, 2);
  }

  function setHtml(id, html) {
    var node = el(id);
    if (node) {
      node.innerHTML = html;
    }
  }

  function query(params) {
    var parts = [];
    Object.keys(params).forEach(function (key) {
      var value = params[key];
      if (value === null || value === undefined || value === '') {
        return;
      }
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
    });
    return parts.length ? '?' + parts.join('&') : '';
  }

  var toastTimer = null;

  function toast(message, kind) {
    var box = el('toast');
    if (!box) {
      return;
    }
    box.innerHTML = '<div class="msg ' + (kind || 'ok') + '">' + esc(message) + '</div>';
    if (toastTimer) {
      clearTimeout(toastTimer);
    }
    toastTimer = setTimeout(function () {
      box.innerHTML = '';
    }, kind === 'error' ? 12000 : 4000);
  }

  function operator() {
    var input = el('operator');
    var value = input ? input.value.trim() : '';
    return value || 'ifmap-ui';
  }

  function requestId() {
    return 'ui-' + Date.now().toString(36) + '-' + Math.floor(Math.random() * 1e6).toString(36);
  }

  function errorMessage(status, payload, raw) {
    if (payload) {
      var message = payload.error || payload.message || ('HTTP ' + status);
      if (payload.errors && payload.errors.length) {
        message += '：' + payload.errors.join('；');
      }
      return message;
    }
    return 'HTTP ' + status + (raw ? '：' + raw.slice(0, 300) : '');
  }

  /** 统一请求：BASE 前缀 + 审计请求头 + 错误归一化（HTTP 状态码语义见 docs/07-管理端REST.md）。 */
  function api(method, path, body) {
    var options = {
      method: method,
      headers: {
        Accept: 'application/json',
        'X-Operator-Id': operator(),
        'X-Request-Id': requestId()
      }
    };
    if (body !== undefined && body !== null) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    return fetch(BASE + path, options).then(function (response) {
      return response.text().then(function (raw) {
        var payload = null;
        if (raw) {
          try {
            payload = JSON.parse(raw);
          } catch (ignored) {
            payload = null;
          }
        }
        if (!response.ok) {
          var error = new Error(errorMessage(response.status, payload, raw));
          error.status = response.status;
          error.payload = payload;
          throw error;
        }
        return payload;
      });
    });
  }

  function fail(error) {
    toast(error && error.message ? error.message : String(error), 'error');
    if (window.console && console.error) {
      console.error(error);
    }
  }

  // ---------------------------------------------------------------- 渲染零件

  function table(headers, rows) {
    if (!rows || !rows.length) {
      return '<p class="muted">（无数据）</p>';
    }
    var head = headers.map(function (h) {
      return '<th>' + esc(h) + '</th>';
    }).join('');
    return '<table><thead><tr>' + head + '</tr></thead><tbody>' + rows.join('') + '</tbody></table>';
  }

  function chips(values) {
    if (!values || !values.length) {
      return '<span class="muted">（无）</span>';
    }
    return '<div class="chips">' + values.map(function (value) {
      return '<span class="chip">' + esc(value) + '</span>';
    }).join('') + '</div>';
  }

  function statusLabel(status) {
    if (text(status) === '1') {
      return '<span class="msg ok" style="padding:0 6px">启用</span>';
    }
    if (text(status) === '0') {
      return '<span class="msg warn" style="padding:0 6px">停用</span>';
    }
    return esc(status);
  }

  /** 枚举下拉的数据源：{@code #actions} 取已注册动作，其余按字段名取宿主机字典。 */
  function dictOptions(key) {
    if (!key) {
      return [];
    }
    if (key === '#actions') {
      return (META.actions || []).map(function (action) {
        return { value: action, label: action };
      });
    }
    var list = (META.enums || {})[key] || [];
    return list.map(function (option) {
      return { value: option.value, label: option.value + ' · ' + option.label };
    });
  }

  function fieldHtml(prefix, spec, value) {
    var id = prefix + spec.name;
    var label = '<span class="field-label">' + esc(spec.label) + '</span>';
    if (spec.type === 'textarea') {
      return '<label class="block">' + esc(spec.label) +
        '<textarea id="' + id + '" rows="4" spellcheck="false">' + esc(value) + '</textarea></label>';
    }
    var hint = spec.hint ? '<span class="muted">' + esc(spec.hint) + '</span>' : '';
    if (spec.dict) {
      var options = dictOptions(spec.dict);
      var current = text(value);
      var known = options.some(function (option) {
        return option.value === current;
      });
      var extra = current !== '' && !known
        ? '<option value="' + esc(current) + '" selected>' + esc(current) + ' · (字典未覆盖)</option>' : '';
      var body = '<option value=""' + (current === '' ? ' selected' : '') + '>(空)</option>' +
        options.map(function (option) {
          return '<option value="' + esc(option.value) + '"' + (option.value === current ? ' selected' : '') +
            '>' + esc(option.label) + '</option>';
        }).join('') + extra;
      return '<label class="inline">' + label + '<select id="' + id + '" data-field="' + esc(spec.name) + '">' +
        body + '</select>' + hint + '</label>';
    }
    return '<label class="inline">' + label +
      '<input id="' + id + '" data-field="' + esc(spec.name) + '"' +
      (spec.type === 'number' ? ' type="number"' : '') + ' value="' + esc(value) + '">' + hint + '</label>';
  }

  function readFields(prefix, specs) {
    var out = {};
    specs.forEach(function (spec) {
      var node = el(prefix + spec.name);
      if (!node) {
        return;
      }
      var raw = node.value;
      if (spec.type === 'number') {
        out[spec.name] = raw === '' ? null : Number(raw);
      } else {
        out[spec.name] = raw;
      }
    });
    return out;
  }

  function messages(id, result) {
    var html = '';
    (result.errors || []).forEach(function (message) {
      html += '<div class="msg error">' + esc(message) + '</div>';
    });
    (result.warnings || []).forEach(function (message) {
      html += '<div class="msg warn">' + esc(message) + '</div>';
    });
    if (result.ok) {
      html += '<div class="msg ok">' + esc(result.ok) + '</div>';
    }
    setHtml(id, html);
  }

  function output(id, title, value, kind) {
    var node = el(id);
    if (!node) {
      return;
    }
    node.innerHTML = '<div class="msg ' + (kind || 'ok') + '">' + esc(title) + '</div>' +
      '<pre class="out">' + esc(typeof value === 'string' ? value : json(value)) + '</pre>';
  }

  // ---------------------------------------------------------------- 字段定义

  var CONFIG_FIELDS = [
    { name: 'tenantId', label: '租户ID', type: 'number' },
    { name: 'interfaceNo', label: '接口编号' },
    { name: 'interfaceCode', label: '接口编码' },
    { name: 'interfaceName', label: '接口名称' },
    { name: 'projectCode', label: '项目编号' },
    { name: 'busiNode', label: '业务节点' },
    { name: 'partnerCode', label: '合作机构代码', dict: 'partnerCode' },
    { name: 'partnerName', label: '合作机构名称' },
    { name: 'financingMode', label: '融资模式', dict: 'financingMode' },
    { name: 'frontInterfaceNo', label: '前置接口编号' },
    { name: 'interfaceOrder', label: '执行顺序', type: 'number' },
    { name: 'resultFlag', label: '结果标识字段' },
    { name: 'successValue', label: '成功值' },
    { name: 'strategyName', label: '策略名', dict: 'strategyName' },
    { name: 'status', label: '状态', type: 'number', dict: 'status' },
    { name: 'remark', label: '备注' },
    { name: 'requestParamTemplate', label: '请求参数模板', type: 'textarea' },
    { name: 'responseParamTemplate', label: '响应参数模板', type: 'textarea' }
  ];

  var BRANCH_FIELDS = [
    { name: 'tenantId', label: '租户ID', type: 'number' },
    { name: 'interfaceNo', label: '接口编号' },
    { name: 'methodFlag', label: '动作 method_flag', dict: '#actions', hint: '空 = 不执行动作' },
    { name: 'logicBranchName', label: '分支名称' },
    { name: 'logicBranchFlag', label: '分支标识 logic_branch_flag', hint: '空 = 兜底分支' },
    { name: 'logicBranchValue', label: '分支取值 logic_branch_value' },
    { name: 'logicBranchOrder', label: '分支顺序', type: 'number' },
    { name: 'remark', label: '备注' }
  ];

  var NEW_CONFIG_DEFAULTS = { interfaceOrder: 1, status: 1 };

  // ---------------------------------------------------------------- 配置列表

  function configQuery() {
    return {
      tenantId: el('f-tenantId') ? el('f-tenantId').value.trim() : '',
      interfaceNo: el('f-interfaceNo') ? el('f-interfaceNo').value.trim() : '',
      busiNode: el('f-busiNode') ? el('f-busiNode').value.trim() : '',
      partnerCode: el('f-partnerCode') ? el('f-partnerCode').value.trim() : '',
      status: el('f-status') ? el('f-status').value : '',
      includeDeleted: el('f-includeDeleted') && el('f-includeDeleted').checked ? 'true' : '',
      page: state.page,
      size: state.size
    };
  }

  function loadConfigs(page) {
    state.page = page || 1;
    return api('GET', '/configs' + query(configQuery())).then(function (result) {
      state.rows = (result && result.rows) || [];
      state.total = (result && result.total) || 0;
      renderConfigList();
      renderPager();
      return result;
    }).catch(fail);
  }

  function renderConfigList() {
    var rows = state.rows.map(function (config) {
      return '<tr class="clickable" data-keyid="' + esc(config.keyId) + '">' +
        '<td>' + esc(config.keyId) + '</td>' +
        '<td>' + esc(config.tenantId) + '</td>' +
        '<td>' + esc(config.interfaceNo) + '</td>' +
        '<td>' + esc(config.busiNode) + '</td>' +
        '<td>' + esc(config.partnerCode) + '</td>' +
        '<td>' + esc(config.interfaceOrder) + '</td>' +
        '<td>' + esc(config.strategyName) + '</td>' +
        '<td>' + statusLabel(config.status) + '</td>' +
        '<td>' + esc(config.version) + '</td>' +
        '<td><button type="button" class="mini danger" data-act="delete" data-keyid="' + esc(config.keyId) +
        '">删除</button></td></tr>';
    });
    setHtml('config-list', table(
      ['keyId', '租户', '接口编号', '业务节点', '银行', '顺序', '策略', '状态', '版本', ''],
      rows));
  }

  function renderPager() {
    var pages = Math.max(1, Math.ceil(state.total / state.size));
    var node = el('config-pager');
    if (!node) {
      return;
    }
    node.innerHTML = '<span class="muted">共 ' + state.total + ' 条，第 ' + state.page + '/' + pages + ' 页</span>' +
      '<button type="button" id="btn-page-prev"' + (state.page <= 1 ? ' disabled' : '') + '>上一页</button>' +
      '<button type="button" id="btn-page-next"' + (state.page >= pages ? ' disabled' : '') + '>下一页</button>';
    if (el('btn-page-prev')) {
      el('btn-page-prev').onclick = function () {
        loadConfigs(state.page - 1);
      };
    }
    if (el('btn-page-next')) {
      el('btn-page-next').onclick = function () {
        loadConfigs(state.page + 1);
      };
    }
  }

  // ---------------------------------------------------------------- 配置编辑

  function renderEditor(config) {
    state.current = config || null;
    var isNew = !config || !config.keyId;
    if (el('editor-title')) {
      el('editor-title').textContent = isNew ? '新建配置' : ('配置 #' + config.keyId + ' · ' + text(config.interfaceNo));
    }
    if (el('editor-meta')) {
      el('editor-meta').textContent = isNew ? '未保存（新建）'
        : ('version=' + text(config.version) + ' status=' + text(config.status) +
          ' delStatus=' + text(config.delStatus) + ' modify=' + text(config.modifyUserId) +
          ' @ ' + text(config.modifyTime));
    }
    setHtml('editor-fields', CONFIG_FIELDS.map(function (spec) {
      var value = config ? config[spec.name] : NEW_CONFIG_DEFAULTS[spec.name];
      return fieldHtml('ed-', spec, value === undefined ? '' : value);
    }).join(''));
    setHtml('editor-messages', '');
    setHtml('history-box', '');
    if (el('editor-reason')) {
      el('editor-reason').value = '';
    }
  }

  function currentConfigPayload() {
    var config = readFields('ed-', CONFIG_FIELDS);
    if (state.current && state.current.keyId) {
      config.keyId = state.current.keyId;
    }
    return config;
  }

  function needCurrent(action) {
    if (!state.current || !state.current.keyId) {
      toast('请先在列表里选中一条已保存的配置，再执行' + action, 'error');
      return false;
    }
    return true;
  }

  function validateConfig() {
    var payload = currentConfigPayload();
    return api('POST', '/configs/validate', {
      config: payload,
      isCreate: !(state.current && state.current.keyId)
    }).then(function (result) {
      messages('editor-messages', { ok: '校验通过', warnings: result.warnings || [] });
      return result;
    }).catch(function (error) {
      if (error.payload && (error.payload.errors || error.payload.warnings)) {
        messages('editor-messages', { errors: error.payload.errors || [], warnings: error.payload.warnings || [] });
      } else {
        messages('editor-messages', { errors: [error.message] });
      }
    });
  }

  function saveConfig() {
    var config = currentConfigPayload();
    var body = {
      config: config,
      reason: el('editor-reason') ? el('editor-reason').value.trim() : ''
    };
    var promise;
    if (state.current && state.current.keyId) {
      body.expectedVersion = state.current.version;
      promise = api('PUT', '/configs/' + state.current.keyId, body);
    } else {
      promise = api('POST', '/configs', body);
    }
    return promise.then(function (result) {
      if (result && result.ok === false) {
        messages('editor-messages', { errors: ['保存被拒绝（乐观锁冲突：配置已被他人改过），请刷新后重试'] });
        return result;
      }
      toast('已保存');
      var keyId = result && result.keyId ? result.keyId
        : (state.current ? state.current.keyId : null);
      return loadConfigs(state.page).then(function () {
        return keyId ? loadConfig(keyId) : null;
      });
    }).catch(function (error) {
      if (error.payload && error.payload.errors) {
        messages('editor-messages', { errors: error.payload.errors, warnings: error.payload.warnings || [] });
      } else {
        messages('editor-messages', { errors: [error.message] });
      }
      fail(error);
    });
  }

  function loadConfig(keyId) {
    return api('GET', '/configs/' + keyId).then(function (config) {
      renderEditor(config);
      return config;
    }).catch(fail);
  }

  function dryRun() {
    var paramsText = el('dry-params') ? el('dry-params').value.trim() : '';
    var params = {};
    if (paramsText) {
      try {
        params = JSON.parse(paramsText);
      } catch (error) {
        messages('editor-messages', { errors: ['样例参数不是合法 JSON：' + error.message] });
        return null;
      }
    }
    var current = state.current || {};
    return api('POST', '/configs/dry-run', {
      tenantId: valueOf('dry-tenantId', current.tenantId),
      interfaceNo: valueOf('dry-interfaceNo', current.interfaceNo),
      busiNode: valueOf('dry-busiNode', current.busiNode),
      bizId: valueOf('dry-bizId', ''),
      params: params,
      mockResponse: el('dry-mock') ? el('dry-mock').value : ''
    }).then(function (result) {
      output('editor-messages', '试跑结果（未出网）', result);
      return result;
    }).catch(function (error) {
      output('editor-messages', '试跑失败：' + error.message, error.payload || '', 'error');
    });
  }

  function valueOf(id, fallback) {
    var node = el(id);
    var value = node ? node.value.trim() : '';
    return value || (fallback === undefined || fallback === null ? '' : text(fallback));
  }

  function loadHistory() {
    if (!needCurrent('历史的查看')) {
      return null;
    }
    return api('GET', '/configs/' + state.current.keyId + '/history').then(function (list) {
      var rows = (list || []).map(function (item) {
        return '<tr><td>' + esc(item.addTime) + '</td><td>' + esc(item.changeType) + '</td>' +
          '<td>' + esc(item.operatorId) + '</td><td>' + esc(item.changeReason) + '</td>' +
          '<td><button type="button" class="mini" data-act="rollback" data-history-id="' + esc(item.keyId) +
          '">回滚到此处</button></td></tr>';
      });
      setHtml('history-box', '<h3>变更历史（最新在前）</h3>' +
        table(['时间', '类型', '操作人', '原因', ''], rows));
      return list;
    }).catch(fail);
  }

  function rollback(historyId) {
    if (!needCurrent('回滚')) {
      return null;
    }
    if (!window.confirm('确认回滚到历史版本 #' + historyId + '？回滚本身也会记一条变更历史。')) {
      return null;
    }
    return api('POST', '/configs/' + state.current.keyId + '/rollback', {
      historyId: Number(historyId),
      expectedVersion: state.current.version,
      reason: el('editor-reason') ? el('editor-reason').value.trim() : ''
    }).then(function (result) {
      if (result && result.ok === false) {
        messages('editor-messages', { errors: ['回滚被拒绝（乐观锁冲突），请刷新后重试'] });
        return result;
      }
      toast('已回滚');
      return loadConfigs(state.page).then(function () {
        return loadConfig(state.current.keyId);
      });
    }).catch(fail);
  }

  function toggleStatus() {
    if (!needCurrent('启停')) {
      return null;
    }
    var next = text(state.current.status) === '1' ? 0 : 1;
    if (!window.confirm('确认把配置 #' + state.current.keyId + ' 改为「' + (next === 1 ? '启用' : '停用') + '」？')) {
      return null;
    }
    return api('POST', '/configs/' + state.current.keyId + '/status', {
      status: next,
      expectedVersion: state.current.version,
      reason: el('editor-reason') ? el('editor-reason').value.trim() : ''
    }).then(function (result) {
      if (result && result.ok === false) {
        messages('editor-messages', { errors: ['启停被拒绝（乐观锁冲突），请刷新后重试'] });
        return result;
      }
      toast('已' + (next === 1 ? '启用' : '停用'));
      return loadConfigs(state.page).then(function () {
        return loadConfig(state.current.keyId);
      });
    }).catch(fail);
  }

  function deleteConfig(keyId) {
    if (!window.confirm('确认逻辑删除配置 #' + keyId + '？（可回滚，物理行保留）')) {
      return null;
    }
    var reason = el('editor-reason') ? el('editor-reason').value.trim() : '';
    return api('DELETE', '/configs/' + keyId + query({ reason: reason })).then(function (result) {
      if (result && result.ok === false) {
        messages('editor-messages', { errors: ['删除被拒绝（目标可能已删除）'] });
        return result;
      }
      toast('已逻辑删除');
      renderEditor(null);
      return loadConfigs(state.page);
    }).catch(fail);
  }

  // ---------------------------------------------------------------- 逻辑分支

  function loadBranches() {
    var filters = {
      tenantId: el('b-tenantId') ? el('b-tenantId').value.trim() : '',
      interfaceNo: el('b-interfaceNo') ? el('b-interfaceNo').value.trim() : '',
      includeDeleted: el('b-includeDeleted') && el('b-includeDeleted').checked ? 'true' : ''
    };
    return api('GET', '/branches' + query(filters)).then(function (list) {
      var rows = (list || []).map(function (branch) {
        return '<tr class="clickable" data-branch-id="' + esc(branch.keyId) + '">' +
          '<td>' + esc(branch.keyId) + '</td>' +
          '<td>' + esc(branch.tenantId) + '</td>' +
          '<td>' + esc(branch.interfaceNo) + '</td>' +
          '<td>' + esc(branch.methodFlag) + '</td>' +
          '<td>' + esc(branch.logicBranchName) + '</td>' +
          '<td>' + (text(branch.logicBranchFlag) === '' ? '<span class="muted">(兜底)</span>' : esc(branch.logicBranchFlag)) + '</td>' +
          '<td>' + esc(branch.logicBranchValue) + '</td>' +
          '<td>' + esc(branch.logicBranchOrder) + '</td>' +
          '<td><button type="button" class="mini danger" data-act="delete-branch" data-branch-id="' +
          esc(branch.keyId) + '">删除</button></td></tr>';
      });
      setHtml('branch-list', table(
        ['keyId', '租户', '接口编号', '动作', '分支名称', '分支标识', '取值', '顺序', ''], rows));
      return list;
    }).catch(fail);
  }

  function renderBranchEditor(branch) {
    state.branchCurrent = branch || null;
    var isNew = !branch || !branch.keyId;
    if (el('branch-editor-title')) {
      el('branch-editor-title').textContent = isNew ? '新建分支' : ('分支 #' + branch.keyId);
    }
    if (el('branch-meta')) {
      el('branch-meta').textContent = isNew ? '未保存（新建）'
        : ('delStatus=' + text(branch.delStatus) + ' 修改人=' + text(branch.modifyUserId));
    }
    setHtml('branch-fields', BRANCH_FIELDS.map(function (spec) {
      var value = branch ? branch[spec.name] : '';
      return fieldHtml('br-', spec, value === undefined ? '' : value);
    }).join(''));
    setHtml('branch-messages', '');
    if (el('branch-reason')) {
      el('branch-reason').value = '';
    }
  }

  function saveBranch() {
    var branch = readFields('br-', BRANCH_FIELDS);
    var body = { branch: branch, reason: el('branch-reason') ? el('branch-reason').value.trim() : '' };
    var promise;
    if (state.branchCurrent && state.branchCurrent.keyId) {
      branch.keyId = state.branchCurrent.keyId;
      promise = api('PUT', '/branches/' + branch.keyId, body);
    } else {
      promise = api('POST', '/branches', body);
    }
    return promise.then(function (result) {
      if (result && result.ok === false) {
        messages('branch-messages', { errors: [result.message || '保存被拒绝（校验未通过）'] });
        return result;
      }
      toast('分支已保存');
      var keyId = result && result.keyId ? result.keyId : state.branchCurrent.keyId;
      return loadBranches().then(function () {
        return api('GET', '/branches' + query({ tenantId: branch.tenantId, interfaceNo: branch.interfaceNo }))
          .then(function (list) {
            var found = (list || []).filter(function (item) {
              return text(item.keyId) === text(keyId);
            })[0];
            if (found) {
              renderBranchEditor(found);
            }
          });
      });
    }).catch(function (error) {
      if (error.payload && error.payload.errors) {
        messages('branch-messages', { errors: error.payload.errors, warnings: error.payload.warnings || [] });
      } else {
        messages('branch-messages', { errors: [error.message] });
      }
      fail(error);
    });
  }

  function deleteBranch(branchId) {
    if (!window.confirm('确认逻辑删除分支 #' + branchId + '？')) {
      return null;
    }
    return api('DELETE', '/branches/' + branchId).then(function (result) {
      if (result && result.ok === false) {
        messages('branch-messages', { errors: ['删除被拒绝（目标可能已删除）'] });
        return result;
      }
      toast('分支已删除');
      renderBranchEditor(null);
      return loadBranches();
    }).catch(fail);
  }

  // ---------------------------------------------------------------- 巡检

  function runAudit() {
    var filters = {
      tenantId: el('a-tenantId') ? el('a-tenantId').value.trim() : '',
      busiNode: el('a-busiNode') ? el('a-busiNode').value.trim() : ''
    };
    return api('GET', '/audit' + query(filters)).then(function (report) {
      var html = '<p>' + (report.clean ? '<span class="msg ok">巡检通过</span>' : '<span class="msg error">发现问题</span>') +
        ' <span class="muted">配置 ' + esc(report.configCount) + ' 条 / 分支 ' + esc(report.branchCount) + ' 条' +
        (report.truncated ? '（已截断，仅扫描了前 N 条）' : '') + '</span></p>';
      if (report.errors && report.errors.length) {
        html += report.errors.map(function (message) {
          return '<div class="msg error">' + esc(message) + '</div>';
        }).join('');
      }
      if (report.warnings && report.warnings.length) {
        html += report.warnings.map(function (message) {
          return '<div class="msg warn">' + esc(message) + '</div>';
        }).join('');
      }
      setHtml('audit-box', html);
      return report;
    }).catch(function (error) {
      output('audit-box', '巡检失败：' + error.message, '', 'error');
    });
  }

  function copyAuditMarkdown() {
    var filters = {
      tenantId: el('a-tenantId') ? el('a-tenantId').value.trim() : '',
      busiNode: el('a-busiNode') ? el('a-busiNode').value.trim() : '',
      markdown: 'true'
    };
    return api('GET', '/audit' + query(filters)).then(function (result) {
      return copyText((result && result.markdown) || '').then(function () {
        toast('Markdown 报告已复制到剪贴板');
      });
    }).catch(fail);
  }

  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(value);
    }
    var area = document.createElement('textarea');
    area.value = value;
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand('copy');
    } finally {
      document.body.removeChild(area);
    }
    return Promise.resolve();
  }

  // ---------------------------------------------------------------- 元数据

  function loadMeta() {
    return Promise.all([
      api('GET', '/rules'),
      api('GET', '/actions'),
      api('GET', '/strategies'),
      api('GET', '/enums')
    ]).then(function (results) {
      META.rules = results[0] || [];
      META.actions = results[1] || [];
      META.strategies = results[2] || {};
      META.enums = results[3] || {};
      renderMeta();
      return META;
    }).catch(fail);
  }

  function renderMeta() {
    setHtml('meta-rules', table(['规则', '签名', '说明', '示例'], (META.rules || []).map(function (rule) {
      return '<tr><td><code>' + esc(rule.name) + '</code></td><td><code>' + esc(rule.signature) +
        '</code></td><td>' + esc(rule.desc) + '</td><td><code>' + esc(rule.example) + '</code></td></tr>';
    })));
    setHtml('meta-actions', chips(META.actions));
    var strategies = META.strategies || {};
    setHtml('meta-strategies', ['specialDeals', 'fullParams', 'logicBranches', 'actions', 'callbacks'].map(function (key) {
      return '<div class="row"><span class="muted" style="min-width:130px">' + esc(key) + '</span>' +
        chips(strategies[key]) + '</div>';
    }).join(''));
    renderEnums();
  }

  function renderEnums() {
    var keys = Object.keys(META.enums || {});
    if (!keys.length) {
      setHtml('meta-enums', '<p class="muted">宿主机未注册 <code>IfmapEnumProvider</code>：' +
        '页面里相关字段用文本输入（不注册也完全可用，见 docs/07-管理端REST.md）。</p>');
      return;
    }
    setHtml('meta-enums', table(['字段', '可选项'], keys.map(function (key) {
      var options = (META.enums[key] || []).map(function (option) {
        return '<span class="chip">' + esc(option.value) + ' · ' + esc(option.label) + '</span>';
      }).join(' ');
      return '<tr><td><code>' + esc(key) + '</code></td><td><div class="chips">' + options + '</div></td></tr>';
    })));
  }

  // ---------------------------------------------------------------- 事件绑定

  function bindTabs() {
    var nav = el('tabs');
    if (!nav) {
      return;
    }
    nav.addEventListener('click', function (event) {
      var button = event.target.closest ? event.target.closest('button[data-tab]') : null;
      if (!button) {
        return;
      }
      var name = button.getAttribute('data-tab');
      Array.prototype.forEach.call(nav.querySelectorAll('button[data-tab]'), function (item) {
        item.classList.toggle('active', item === button);
      });
      ['configs', 'branches', 'audit', 'meta'].forEach(function (tab) {
        var panel = el('tab-' + tab);
        if (panel) {
          panel.classList.toggle('hidden', tab !== name);
        }
      });
    });
  }

  function bindConfigs() {
    var filter = el('config-filter');
    if (filter) {
      filter.addEventListener('submit', function (event) {
        event.preventDefault();
        loadConfigs(1);
      });
    }
    if (el('btn-config-new')) {
      el('btn-config-new').onclick = function () {
        renderEditor(null);
        toast('已切到新建模式，填完点「保存」');
      };
    }
    var list = el('config-list');
    if (list) {
      list.addEventListener('click', function (event) {
        var button = event.target.closest ? event.target.closest('button[data-act]') : null;
        if (button) {
          event.stopPropagation();
          deleteConfig(button.getAttribute('data-keyid'));
          return;
        }
        var row = event.target.closest ? event.target.closest('tr[data-keyid]') : null;
        if (row) {
          loadConfig(row.getAttribute('data-keyid'));
        }
      });
    }
    if (el('btn-editor-validate')) {
      el('btn-editor-validate').onclick = validateConfig;
    }
    if (el('btn-editor-save')) {
      el('btn-editor-save').onclick = saveConfig;
    }
    if (el('btn-editor-dryrun')) {
      el('btn-editor-dryrun').onclick = dryRun;
    }
    if (el('btn-editor-history')) {
      el('btn-editor-history').onclick = loadHistory;
    }
    if (el('btn-editor-status')) {
      el('btn-editor-status').onclick = toggleStatus;
    }
    if (el('btn-editor-delete')) {
      el('btn-editor-delete').onclick = function () {
        if (needCurrent('删除')) {
          deleteConfig(state.current.keyId);
        }
      };
    }
    var history = el('history-box');
    if (history) {
      history.addEventListener('click', function (event) {
        var button = event.target.closest ? event.target.closest('button[data-act="rollback"]') : null;
        if (button) {
          rollback(button.getAttribute('data-history-id'));
        }
      });
    }
  }

  function bindBranches() {
    var filter = el('branch-filter');
    if (filter) {
      filter.addEventListener('submit', function (event) {
        event.preventDefault();
        loadBranches();
      });
    }
    if (el('btn-branch-new')) {
      el('btn-branch-new').onclick = function () {
        var current = state.current;
        var branch = { tenantId: current ? current.tenantId : '', interfaceNo: current ? current.interfaceNo : '' };
        renderBranchEditor(branch);
      };
    }
    if (el('btn-branch-save')) {
      el('btn-branch-save').onclick = saveBranch;
    }
    if (el('btn-branch-delete')) {
      el('btn-branch-delete').onclick = function () {
        if (state.branchCurrent && state.branchCurrent.keyId) {
          deleteBranch(state.branchCurrent.keyId);
        } else {
          toast('请先选中一条已保存的分支', 'error');
        }
      };
    }
    var list = el('branch-list');
    if (list) {
      list.addEventListener('click', function (event) {
        var button = event.target.closest ? event.target.closest('button[data-act="delete-branch"]') : null;
        if (button) {
          event.stopPropagation();
          deleteBranch(button.getAttribute('data-branch-id'));
          return;
        }
        var row = event.target.closest ? event.target.closest('tr[data-branch-id]') : null;
        if (!row) {
          return;
        }
        var branchId = row.getAttribute('data-branch-id');
        api('GET', '/branches' + query({
          tenantId: el('b-tenantId') ? el('b-tenantId').value.trim() : '',
          interfaceNo: el('b-interfaceNo') ? el('b-interfaceNo').value.trim() : '',
          includeDeleted: 'true'
        })).then(function (all) {
          var found = (all || []).filter(function (item) {
            return text(item.keyId) === text(branchId);
          })[0];
          if (found) {
            renderBranchEditor(found);
          } else {
            toast('没找到分支 #' + branchId, 'error');
          }
        }).catch(fail);
      });
    }
  }

  function bindAudit() {
    if (el('btn-audit-run')) {
      el('btn-audit-run').onclick = runAudit;
    }
    if (el('btn-audit-copy')) {
      el('btn-audit-copy').onclick = copyAuditMarkdown;
    }
  }

  function init() {
    setHtml('base-path-label', '路径前缀 ' + (BASE || '(空)'));
    var input = el('operator');
    if (input) {
      try {
        input.value = window.localStorage ? (window.localStorage.getItem('ifmap.operator') || '') : '';
      } catch (ignored) {
        input.value = '';
      }
      input.addEventListener('change', function () {
        try {
          if (window.localStorage) {
            window.localStorage.setItem('ifmap.operator', input.value.trim());
          }
        } catch (ignored) {
          /* 隐私模式下 localStorage 不可用：不影响功能 */
        }
      });
    }
    bindTabs();
    bindConfigs();
    bindBranches();
    bindAudit();
    renderEditor(null);
    renderBranchEditor(null);
    loadMeta().then(function () {
      return loadConfigs(1);
    });
    loadBranches();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
