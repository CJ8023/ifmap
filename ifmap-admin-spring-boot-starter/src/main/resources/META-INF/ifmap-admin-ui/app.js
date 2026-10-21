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

  // ---------------------------------------------------------------- 判定规格编辑器

  /** 条件算子（顺序与后端 {@code JudgeOp} 一致；只有 always 走「不判定」单选项）。 */
  var JUDGE_OPS = ['eq', 'ne', 'in', 'notIn', 'exists', 'notBlank', 'regex'];

  /** value 是**标量数组**的算子。 */
  var JUDGE_LIST_OPS = { in: true, notIn: true };

  /** 不需要 value 的算子（存在性 / 非空）。 */
  var JUDGE_NO_VALUE_OPS = { exists: true, notBlank: true };

  var JUDGE_ALWAYS = '{\"op\":\"always\"}';

  /**
   * 把规格文本拆成「三选一」的表单状态。
   *
   * <p>表单只覆盖两种能被"点选"表达的形态：{@code always} 与**单条件**组合
   * （{@code {"all":[条件]}}）。嵌套 {@code any}、多条件、带 {@code ignoreCase} 之外的花样
   * 一律落到 {@code raw} 模式 —— 即「非法态提示」：原样给出 JSON，让人看见它不再是简单形态，
   * 而不是悄悄丢掉条件（静默丢条件 = 判定变宽，最难查的一类事故）。</p>
   */
  function judgeSpecParts(raw) {
    var value = text(raw).trim();
    if (value === '') {
      return { mode: 'always', condition: null, note: '未配置时按 always 处理（保存前请显式确认）' };
    }
    var parsed;
    try {
      parsed = JSON.parse(value);
    } catch (e) {
      return { mode: 'raw', condition: null, note: '当前内容不是合法 JSON，切到原始 JSON 模式修正' };
    }
    if (!parsed || typeof parsed !== 'object') {
      return { mode: 'raw', condition: null, note: '规格必须是 JSON 对象' };
    }
    if (parsed.op === 'always' && Object.keys(parsed).length === 1) {
      return { mode: 'always', condition: null, note: '' };
    }
    if (Array.isArray(parsed.all) && parsed.all.length === 1) {
      var condition = parsed.all[0];
      if (condition && typeof condition === 'object' && typeof condition.path === 'string'
        && JUDGE_OPS.indexOf(condition.op) >= 0 && !condition.all && !condition.any) {
        return { mode: 'condition', condition: condition, note: '' };
      }
    }
    return {
      mode: 'raw',
      condition: null,
      note: '这条规格不是「单条件」形态（多条件 / any 嵌套 / 多组 all），已切到原始 JSON 模式：' +
        '改用表单会丢掉条件，判定就会被放宽'
    };
  }

  function judgeOpOptions(current) {
    return JUDGE_OPS.map(function (op) {
      return '<option value="' + op + '"' + (op === current ? ' selected' : '') + '>' + op + '</option>';
    }).join('');
  }

  function judgeFieldHtml(prefix, spec, value) {
    var id = prefix + spec.name;
    var parts = judgeSpecParts(value);
    var condition = parts.condition || {};
    var op = JUDGE_OPS.indexOf(condition.op) >= 0 ? condition.op : 'eq';
    var valueText = '';
    if (Object.prototype.hasOwnProperty.call(condition, 'value')) {
      valueText = Array.isArray(condition.value) ? condition.value.join(', ') : String(condition.value);
    }
    var radio = function (mode, label, hint) {
      return '<label class="inline"><input type="radio" name="' + id + '-mode" value="' + mode + '"' +
        (parts.mode === mode ? ' checked' : '') + '> ' + esc(label) +
        (hint ? ' <span class="muted">' + esc(hint) + '</span>' : '') + '</label>';
    };
    var hidden = parts.mode === 'condition' ? '' : ' style="display:none"';
    var rawHidden = parts.mode === 'raw' ? '' : ' style="display:none"';
    return '<div class="block judge-editor">' +
      '<span class="field-label">' + esc(spec.label) + '</span>' +
      '<span class="muted">单列取代 result_flag + success_value：不需要判断结果就选「不判定」，' +
      '但那条接口从此只看通道是否通</span>' +
      '<div class="judge-modes">' +
      radio('always', '不判定', '{"op":"always"}') +
      radio('condition', '单条件', 'path + op + value') +
      radio('raw', '原始 JSON', '复杂组合') +
      '</div>' +
      '<div id="ed-judge-condition"' + hidden + '>' +
      '<input id="ed-judge-path" data-field="judgePath" placeholder="$.code" value="' +
      esc(condition.path || '') + '">' +
      '<select id="ed-judge-op" data-field="judgeOp">' + judgeOpOptions(op) + '</select>' +
      '<input id="ed-judge-value" data-field="judgeValue" placeholder="值（in/notIn 用英文逗号分隔）" value="' +
      esc(valueText) + '">' +
      '<label class="inline"><input type="checkbox" id="ed-judge-ignoreCase"' +
      (condition.ignoreCase ? ' checked' : '') + '> 忽略大小写</label>' +
      '</div>' +
      '<textarea id="ed-judge-raw" rows="3" spellcheck="false"' + rawHidden + '>' + esc(text(value)) +
      '</textarea>' +
      '<span class="muted" id="ed-judge-hint">' + esc(parts.note) + '</span>' +
      '</div>';
  }

  /** 读取判定形态（三个单选项之一）。 */
  function judgeMode() {
    var node = document.querySelector('input[name="ed-resultJudge-mode"]:checked');
    return node ? node.value : 'always';
  }

  /** 判定提示：按 op 说明 value 该填什么（表单不替人猜语义）。 */
  function judgeHint() {
    var mode = judgeMode();
    if (mode === 'always') {
      return '不判定：任何响应都按成功处理（只受通道 / HTTP 状态约束）';
    }
    if (mode === 'raw') {
      return '原始 JSON 直接提交，保存前由服务端校验（非法会 422）';
    }
    var opNode = el('ed-judge-op');
    var op = opNode ? opNode.value : 'eq';
    if (JUDGE_NO_VALUE_OPS[op]) {
      return 'op=' + op + ' 不需要 value';
    }
    if (JUDGE_LIST_OPS[op]) {
      return 'op=' + op + ' 的 value 是数组：多个值用英文逗号分隔';
    }
    return 'op=' + op + ' 需要一个非空 value';
  }

  /** 切换形态 / 改 op 时同步可见区与提示。 */
  function syncJudgeEditor() {
    var mode = judgeMode();
    var condition = el('ed-judge-condition');
    var raw = el('ed-judge-raw');
    var hint = el('ed-judge-hint');
    if (condition) {
      condition.style.display = mode === 'condition' ? '' : 'none';
    }
    if (raw) {
      raw.style.display = mode === 'raw' ? '' : 'none';
    }
    if (hint) {
      hint.textContent = judgeHint();
    }
    if (mode === 'condition' && el('ed-judge-op')) {
      var op = el('ed-judge-op').value;
      var value = el('ed-judge-value');
      if (value) {
        value.disabled = !!JUDGE_NO_VALUE_OPS[op];
        if (value.disabled) {
          value.value = '';
        }
        value.placeholder = JUDGE_LIST_OPS[op] ? '多个值用英文逗号分隔' : '值';
      }
    }
  }

  /** 收集判定规格文本（保存/校验前调用，覆盖 readFields 里的空位）。 */
  function readJudgeSpec() {
    var mode = judgeMode();
    if (mode === 'always') {
      return JUDGE_ALWAYS;
    }
    if (mode === 'raw') {
      return el('ed-judge-raw') ? el('ed-judge-raw').value.trim() : '';
    }
    var path = el('ed-judge-path') ? el('ed-judge-path').value.trim() : '';
    var op = el('ed-judge-op') ? el('ed-judge-op').value : 'eq';
    var condition = { path: path, op: op };
    if (!JUDGE_NO_VALUE_OPS[op]) {
      var raw = el('ed-judge-value') ? el('ed-judge-value').value : '';
      if (JUDGE_LIST_OPS[op]) {
        condition.value = raw.split(',').map(function (item) {
          return item.trim();
        }).filter(function (item) {
          return item !== '';
        });
      } else {
        condition.value = raw;
      }
    }
    if (el('ed-judge-ignoreCase') && el('ed-judge-ignoreCase').checked) {
      condition.ignoreCase = true;
    }
    return json({ all: [condition] });
  }

  function fieldHtml(prefix, spec, value) {
    var id = prefix + spec.name;
    var label = '<span class="field-label">' + esc(spec.label) + '</span>';
    if (spec.type === 'judge') {
      return judgeFieldHtml(prefix, spec, value);
    }
    if (spec.type === 'textarea') {
      return '<label class="block">' + esc(spec.label) +
        '<textarea id="' + id + '" rows="4" spellcheck="false">' + esc(value) + '</textarea></label>';
    }
    var hint = spec.hint ? '<span class="muted">' + esc(spec.hint) + '</span>' : '';
    if (spec.dict) {
      var options = dictOptions(spec.dict);
      if (options.length) {
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
      // 字典是空的（宿主没注册 IfmapEnumProvider、也没内置值）：退化成输入框。
      // 千万别在这里仍渲染 select —— 只剩一个「(空)」选项等于把字段锁死，用户连手输都做不到。
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
    { name: 'interfaceOrder', label: '执行顺序', type: 'number' },
    { name: 'judgeName', label: '判定器', hint: '空 = ruleSpec' },
    { name: 'resultJudge', label: '结果判定 result_judge', type: 'judge' },
    { name: 'preStrategyName', label: '前序策略', dict: 'strategyName' },
    { name: 'postStrategyName', label: '后序策略', dict: 'strategyName' },
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
        '<td>' + esc(config.preStrategyName) + '</td>' +
        '<td>' + esc(config.postStrategyName) + '</td>' +
        '<td>' + statusLabel(config.status) + '</td>' +
        '<td>' + esc(config.version) + '</td>' +
        '<td><button type="button" class="mini danger" data-act="delete" data-keyid="' + esc(config.keyId) +
        '">删除</button></td></tr>';
    });
    setHtml('config-list', table(
      ['keyId', '租户', '接口编号', '业务节点', '合作机构', '顺序', '前序策略', '后序策略', '状态', '版本', ''],
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
      el('editor-meta').textContent = isNew
        ? '未保存 · 填完点右下「保存」即新建；想改已有的就在上面列表里点一条'
        : ('version=' + text(config.version) + ' status=' + text(config.status) +
          ' delStatus=' + text(config.delStatus) + ' modify=' + text(config.modifyUserId) +
          ' @ ' + text(config.modifyTime));
    }
    // 「清空表单」只在编辑态才有意义：空态已经是新建模式了
    if (el('btn-config-new')) {
      el('btn-config-new').classList.toggle('hidden', isNew);
    }
    setHtml('editor-fields', CONFIG_FIELDS.map(function (spec) {
      var value = config ? config[spec.name] : NEW_CONFIG_DEFAULTS[spec.name];
      return fieldHtml('ed-', spec, value === undefined ? '' : value);
    }).join(''));
    syncJudgeEditor();
    setHtml('editor-messages', '');
    setHtml('history-box', '');
    if (el('editor-reason')) {
      el('editor-reason').value = '';
    }
  }

  function currentConfigPayload() {
    var config = readFields('ed-', CONFIG_FIELDS);
    // resultJudge 由判定编辑器拼出来（readFields 读不到它：没有 ed-resultJudge 文本输入框）
    config.resultJudge = readJudgeSpec();
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
      // historyId 是雪花 ID：后端以字符串下发（超出 2^53，Number() 会把它取整成另一个 id，
      // 撞出来就是「历史记录不存在」。Jackson 收字符串照样能转成 Long，原样传就行。
      historyId: historyId,
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
    // 分支表以 interface_no 为必填条件（一条分支挂在某个接口下），空着就别发请求：
    // 服务端会回 400，页面只会多一个红框。
    if (filters.interfaceNo === '') {
      setHtml('branch-list', '<p class="muted tip">分支按接口编号查：在上面填「接口编号」（或在配置列表里选中一条配置），再点「查询」。</p>');
      return Promise.resolve([]);
    }
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
    setHtml('meta-strategies', ['strategyHooks', 'fullParams', 'logicBranches', 'actions', 'callbacks'].map(function (key) {
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
        toast('已清空表单：现在是新建模式，填完点「保存」');
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
    var fields = el('editor-fields');
    if (fields) {
      fields.addEventListener('change', function (event) {
        var target = event.target;
        if (!target) {
          return;
        }
        if (target.name === 'ed-resultJudge-mode' || target.id === 'ed-judge-op') {
          syncJudgeEditor();
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
