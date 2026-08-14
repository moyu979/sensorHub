/**
 * sensorHub Web 监控面板
 * 使用 Canvas API 绘制实时趋势图
 */

// ===== 状态 =====
const state = {
    fields: [],
    selectedField: '',
    historyData: [],       // {timestamp, value}[]
    historyTimestamps: [],
    chartData: [],         // 纯数值数组
    updateInterval: null,
    serverUrl: window.location.origin,
    historyChartData: {},  // {fieldName: [{timestamp, value}, ...]}
    historyFields: [],     // 已选中的历史查询字段
};

// ===== DOM 引用 =====
const $ = (id) => document.getElementById(id);
const statusBadge = $('statusBadge');
const dataSource = $('dataSource');
const fieldCount = $('fieldCount');
const updateTime = $('updateTime');
const appVersion = $('appVersion');
const sourceToggle = $('sourceToggle');
const sourceModeValue = $('sourceModeValue');
const fieldSelector = $('fieldSelector');
const canvas = $('trendChart');
const ctx = canvas.getContext('2d');

// ===== 工具 =====
function formatTime(ts) {
    const d = new Date(ts);
    return d.toLocaleTimeString('zh-CN', { hour12: false });
}

function formatTimeFull(ts) {
    const d = new Date(ts);
    return d.toLocaleString('zh-CN', { hour12: false });
}

// ===== 网络请求 =====
async function apiFetch(path) {
    try {
        const resp = await fetch(`${state.serverUrl}${path}`);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return await resp.json();
    } catch (err) {
        console.error(`API 请求失败: ${path}`, err);
        throw err;
    }
}

// ===== Canvas 图表绘制 =====
function drawChart() {
    const dpr = window.devicePixelRatio || 1;
    const cw = canvas.clientWidth || 300;
    const ch = canvas.clientHeight || 200;

    // 设置 Canvas 缓冲尺寸（显示尺寸由 CSS 控制）
    canvas.width = cw * dpr;
    canvas.height = ch * dpr;

    ctx.scale(dpr, dpr);

    const W = cw;
    const H = ch;
    const pad = { top: 20, right: 20, bottom: 30, left: 55 };
    const chartW = W - pad.left - pad.right;
    const chartH = H - pad.top - pad.bottom;

    // 清空
    ctx.clearRect(0, 0, W, H);

    const points = state.chartData;
    const maxPoints = 60;

    if (points.length === 0) {
        ctx.fillStyle = '#8888aa';
        ctx.font = '14px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('等待数据...', W / 2, H / 2);
        return;
    }

    // 计算范围
    const dataMin = Math.min(...points);
    const dataMax = Math.max(...points);
    const range = dataMax - dataMin < 0.001 ? 1 : dataMax - dataMin;
    const yMin = dataMin - range * 0.1;
    const yMax = dataMax + range * 0.1;
    const yRange = yMax - yMin;

    // ---- 网格线 ----
    ctx.strokeStyle = '#2a2a3e';
    ctx.lineWidth = 1;
    const gridCount = 4;
    for (let i = 0; i <= gridCount; i++) {
        const y = pad.top + (chartH * i) / gridCount;
        ctx.beginPath();
        ctx.moveTo(pad.left, y);
        ctx.lineTo(W - pad.right, y);
        ctx.stroke();

        // Y 轴标签
        const val = yMax - (yRange * i) / gridCount;
        ctx.fillStyle = '#8888aa';
        ctx.font = '11px sans-serif';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        ctx.fillText(val.toFixed(1), pad.left - 8, y);
    }

    // ---- 数据线 ----
    const stepX = chartW / (maxPoints - 1);
    const offsetX = points.length < maxPoints
        ? pad.left + chartW - (points.length - 1) * stepX
        : pad.left;

    ctx.beginPath();
    ctx.strokeStyle = '#00d4aa';
    ctx.lineWidth = 2.5;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';

    for (let i = 0; i < points.length; i++) {
        const x = offsetX + i * stepX;
        const normalized = (points[i] - yMin) / yRange;
        const y = pad.top + chartH - normalized * chartH;

        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
    }
    ctx.stroke();

    // ---- 填充 ----
    const lastIdx = points.length - 1;
    const lastX = offsetX + lastIdx * stepX;
    const lastNormalized = (points[lastIdx] - yMin) / yRange;
    const lastY = pad.top + chartH - lastNormalized * chartH;

    ctx.lineTo(lastX, pad.top + chartH);
    ctx.lineTo(offsetX, pad.top + chartH);
    ctx.closePath();

    const gradient = ctx.createLinearGradient(0, pad.top, 0, pad.top + chartH);
    gradient.addColorStop(0, 'rgba(0, 212, 170, 0.2)');
    gradient.addColorStop(1, 'rgba(0, 212, 170, 0.02)');
    ctx.fillStyle = gradient;
    ctx.fill();

    // ---- 当前值圆点 ----
    const curNormalized = (points[lastIdx] - yMin) / yRange;
    const curY = pad.top + chartH - curNormalized * chartH;

    ctx.beginPath();
    ctx.arc(lastX, curY, 5, 0, Math.PI * 2);
    ctx.fillStyle = '#00d4aa';
    ctx.fill();

    // 当前值标签
    ctx.fillStyle = '#00d4aa';
    ctx.font = 'bold 13px sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'bottom';
    ctx.fillText(points[lastIdx].toFixed(2), lastX + 8, curY - 4);

    // ---- X 轴时间标签 ----
    if (state.historyTimestamps.length > 0) {
        ctx.fillStyle = '#8888aa';
        ctx.font = '10px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';

        const firstTs = state.historyTimestamps[0];
        const lastTs = state.historyTimestamps[state.historyTimestamps.length - 1];
        const firstLabelX = points.length < maxPoints ? offsetX : pad.left;

        ctx.fillText(formatTime(firstTs), firstLabelX, pad.top + chartH + 6);
        ctx.fillText(formatTime(lastTs), lastX, pad.top + chartH + 6);
    }
}

// ===== 更新 UI =====
function updateStatus(data) {
    dataSource.textContent = data.data_source || '--';
    fieldCount.textContent = data.field_count ?? '--';
    appVersion.textContent = data.version || '--';

    // 数据源模式（fake/cdc）——与手机端开关同步
    const mode = data.source_mode || 'unknown';
    const isCdc = mode === 'cdc';
    if (sourceModeValue) {
        sourceModeValue.textContent = isCdc ? '真实数据 (USB CDC)' : '模拟数据 (Fake)';
        sourceModeValue.style.color = isCdc ? 'var(--accent)' : 'var(--text-secondary)';
    }
    // 轮询刷新时，避免覆盖用户正在操作的开关
    if (sourceToggle && !sourceSwitching && sourceToggle.checked !== isCdc) {
        sourceToggle.checked = isCdc;
    }
}

function updateFieldSelector(fields) {
    const currentOptions = Array.from(fieldSelector.options).map(o => o.value);
    const sorted = [...fields].sort();
    if (JSON.stringify(currentOptions) === JSON.stringify(sorted)) return;

    fieldSelector.innerHTML = '';
    sorted.forEach(name => {
        const opt = document.createElement('option');
        opt.value = name;
        opt.textContent = name;
        if (name === state.selectedField) opt.selected = true;
        fieldSelector.appendChild(opt);
    });
}

function updateRealtimeData(current) {
    const container = $('realtimeDataList');
    if (!container) return;

    const fields = current.fields || {};
    const entries = Object.entries(fields);
    if (entries.length === 0) {
        container.innerHTML = '<div class="empty">等待数据...</div>';
        return;
    }

    let html = '';
    entries.forEach(([name, value]) => {
        const display = typeof value === 'number' ? value.toFixed(3) : value;
        html += `<div class="data-row">
            <span class="data-name">${name}</span>
            <span class="data-value">${display}</span>
        </div>`;
    });
    container.innerHTML = html;
}

function updateChart(fieldName, history) {
    state.selectedField = fieldName;
    state.historyData = history.data || [];
    state.historyTimestamps = state.historyData.map(d => d.timestamp);
    state.chartData = state.historyData.map(d => d.value);
    drawChart();
}

// ===== 数据轮询 =====
async function pollAll() {
    try {
        // 并行获取状态和当前数据
        const [status, current] = await Promise.all([
            apiFetch('/api/status'),
            apiFetch('/api/current')
        ]);

        statusBadge.textContent = '● 在线';
        statusBadge.className = 'badge';

        // 更新系统状态
        updateStatus(status);

        // 更新时间
        updateTime.textContent = formatTimeFull(Date.now());

        // 更新实时数据列表
        updateRealtimeData(current);

        // 更新字段列表
        const fields = status.fields || [];
        state.fields = fields;
        updateFieldSelector(fields);
        if (historyViewInitDone) updateHistoryFields();

        // 更新实时图表
        const activeField = state.selectedField || (fields.length > 0 ? fields[0] : '');
        if (activeField) {
            try {
                const history = await apiFetch(`/api/history/${activeField}?count=60`);
                updateChart(activeField, history);
            } catch (e) {
                console.warn(`获取实时数据失败: ${activeField}`, e);
            }
        }

    } catch (err) {
        statusBadge.textContent = '● 离线';
        statusBadge.className = 'badge error';
        console.error('轮询失败', err);
    }
}

// ===== 字段选择事件 =====
fieldSelector.addEventListener('change', async (e) => {
    const field = e.target.value;
    if (!field) return;

    state.selectedField = field;
    try {
        const history = await apiFetch(`/api/history/${field}?count=60`);
        updateChart(field, history);
    } catch (err) {
        console.error('切换字段失败', err);
    }
});

// ===== 数据源切换（fake / cdc，与手机端双向同步） =====
let sourceSwitching = false;

if (sourceToggle) {
    sourceToggle.addEventListener('change', async (e) => {
        if (sourceSwitching) return;
        const useCdc = e.target.checked;
        const target = useCdc ? 'cdc' : 'fake';
        sourceSwitching = true;
        sourceToggle.disabled = true;
        try {
            const resp = await fetch(`${state.serverUrl}/api/datasource`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'source=' + encodeURIComponent(target)
            });
            if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
            // 刷新一次状态，让 source_mode / 手机端同步
            await pollAll();
        } catch (err) {
            console.error('切换数据源失败', err);
            e.target.checked = !useCdc;   // 失败回滚
            if (sourceModeValue) {
                sourceModeValue.textContent = useCdc ? '模拟数据 (Fake)' : '真实数据 (USB CDC)';
            }
        } finally {
            sourceToggle.disabled = false;
            setTimeout(() => { sourceSwitching = false; }, 500);
        }
    });
}

// ===== 窗口大小变化 =====
let resizeTimer;
window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
        drawChart();
        drawHistoryChart();
    }, 150);
});

// ================================================================
// 视图切换（实时监控 / 历史查询）
// ================================================================

document.querySelectorAll('.nav-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');

        const target = btn.dataset.view;
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        const view = document.getElementById('view-' + target);
        if (view) view.classList.add('active');

        if (target === 'history') initHistoryView();
        if (target === 'flash') setTimeout(flashCheckDevice, 300);
    });
});

// ================================================================
// 历史查询 — 初始化
// ================================================================

const historyCanvas = $('historyChart');
const historyCtx = historyCanvas ? historyCanvas.getContext('2d') : null;
let historyViewInitDone = false;

function initHistoryView() {
    if (historyViewInitDone) return;

    // 设置默认日期为今天
    const today = new Date();
    const yyyy = today.getFullYear();
    const mm = String(today.getMonth() + 1).padStart(2, '0');
    const dd = String(today.getDate()).padStart(2, '0');
    $('historyStartDate').value = `${yyyy}-${mm}-${dd}`;
    $('historyEndDate').value = `${yyyy}-${mm}-${dd}`;

    updateHistoryFields();

    historyViewInitDone = true;
}

function updateHistoryFields() {
    const container = $('historyFields');
    if (!container) return;

    // 保存当前选中状态
    const checkedMap = {};
    container.querySelectorAll('input[type="checkbox"]').forEach(cb => {
        checkedMap[cb.value] = cb.checked;
    });

    const fields = state.fields.length > 0 ? state.fields
        : ['temperature', 'humidity', 'voltage', 'pressure'];

    let html = '';
    fields.forEach(name => {
        const isChecked = checkedMap[name] !== undefined ? checkedMap[name] : true;
        html += `<label>
            <input type="checkbox" value="${name}" class="history-field-cb" ${isChecked ? 'checked' : ''}>
            ${name}
        </label>`;
    });
    container.innerHTML = html;
}

// ================================================================
// 历史查询 — 执行查询
// ================================================================

$('historyQueryBtn')?.addEventListener('click', async () => {
    const startDate = $('historyStartDate').value;
    const endDate = $('historyEndDate').value;
    const startTime = $('historyStartTime').value;
    const endTime = $('historyEndTime').value;

    if (!startDate || !endDate) {
        alert('请选择起始和结束日期');
        return;
    }

    // 收集选中的字段
    const checked = document.querySelectorAll('.history-field-cb:checked');
    const fields = Array.from(checked).map(cb => cb.value);
    if (fields.length === 0) {
        alert('请至少选择一个传感器字段');
        return;
    }

    state.historyFields = fields;
    const btn = $('historyQueryBtn');
    btn.disabled = true;
    btn.textContent = '查询中...';

    try {
        const params = new URLSearchParams({
            startDate, startTime, endDate, endTime,
            fields: fields.join(',')
        });
        const url = `${state.serverUrl}/api/history/query?${params}`;
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();

        state.historyChartData = data.fields || {};
        drawHistoryChart();
    } catch (err) {
        console.error('历史查询失败', err);
        alert('查询失败: ' + err.message);
    } finally {
        btn.disabled = false;
        btn.textContent = '查询';
    }
});

// ===== 箱宽滑块 =====
$('bucketWidthSlider')?.addEventListener('input', () => {
    const val = $('bucketWidthSlider').value;
    $('bucketWidthLabel').textContent = val === '0' ? '原始' : val;
    if (Object.keys(state.historyChartData).length > 0) {
        drawHistoryChart();
    }
});

// ================================================================
// 历史查询 — 数据聚合
// ================================================================

/**
 * 将原始数据点聚合为等距时间段内的统计值
 * @param {Array} points - [{timestamp, value}, ...]
 * @param {number} tMin - 起始时间戳
 * @param {number} tMax - 结束时间戳
 * @param {number} bucketCount - 分组数
 * @returns {Array} [{timestamp, avg, min, max}, ...]
 */
function aggregatePoints(points, tMin, tMax, bucketCount) {
    if (points.length === 0 || bucketCount <= 0) return [];

    const bucketMs = (tMax - tMin) / bucketCount;
    const buckets = [];

    for (let i = 0; i < bucketCount; i++) {
        const start = tMin + i * bucketMs;
        const end = start + bucketMs;
        const inBucket = points.filter(p => p.timestamp >= start && p.timestamp < end);

        if (inBucket.length === 0) {
            buckets.push({ timestamp: start + bucketMs / 2, avg: null, min: null, max: null });
        } else {
            const vals = inBucket.map(p => p.value);
            const avg = vals.reduce((s, v) => s + v, 0) / vals.length;
            buckets.push({
                timestamp: start + bucketMs / 2,
                avg,
                min: Math.min(...vals),
                max: Math.max(...vals),
            });
        }
    }
    return buckets;
}

// ================================================================
// 历史查询 — 多线图表绘制（归一化显示）
// ================================================================

const HISTORY_COLORS = [
    '#00d4aa', '#00aaff', '#ffd93d', '#ff6b6b',
    '#aa66ff', '#ff88aa', '#66ddaa', '#ffaa44'
];

function drawHistoryChart() {
    if (!historyCanvas || !historyCtx) return;

    // 从父容器取固定尺寸，避免 Canvas 自身尺寸波动
    const parent = historyCanvas.parentElement;
    const rect = parent.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    const cw = rect.width || 300;
    const ch = rect.height || 200;
    // 只在尺寸真正变化时重置 buffer，避免反复清零
    if (historyCanvas.width !== Math.round(cw * dpr) || historyCanvas.height !== Math.round(ch * dpr)) {
        historyCanvas.width = Math.round(cw * dpr);
        historyCanvas.height = Math.round(ch * dpr);
    }
    historyCtx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const W = cw;
    const H = ch;
    const pad = { top: 30, right: 120, bottom: 35, left: 55 };
    const chartW = W - pad.left - pad.right;
    const chartH = H - pad.top - pad.bottom;

    historyCtx.clearRect(0, 0, W, H);

    // 判断模式
    const bucketWidth = parseInt($('bucketWidthSlider').value) || 0;
    const bucketCount = bucketWidth > 0 ? Math.max(2, Math.floor(chartW / bucketWidth)) : 0;
    const isAggregate = bucketCount > 0;

    // 构建系列，过滤空数据
    const seriesMap = {};
    for (const [field, points] of Object.entries(state.historyChartData)) {
        if (points.length > 0) {
            seriesMap[field] = points;
        }
    }

    const fieldNames = Object.keys(seriesMap);

    if (fieldNames.length === 0) {
        historyCtx.fillStyle = '#8888aa';
        historyCtx.font = '14px sans-serif';
        historyCtx.textAlign = 'center';
        historyCtx.fillText('无数据，请选择日期和字段后查询', W / 2, H / 2);
        return;
    }

    // 计算时间范围
    let allTimestamps = [];
    for (const points of Object.values(seriesMap)) {
        points.forEach(p => allTimestamps.push(p.timestamp));
    }
    allTimestamps.sort((a, b) => a - b);
    const tMin = allTimestamps[0];
    const tMax = allTimestamps[allTimestamps.length - 1];
    const tRange = tMax - tMin || 1;

    // 聚合（如果需要）
    const plotData = {};
    if (isAggregate) {
        for (const field of fieldNames) {
            plotData[field] = aggregatePoints(seriesMap[field], tMin, tMax, bucketCount);
        }
    } else {
        for (const field of fieldNames) {
            plotData[field] = seriesMap[field].map(p => ({
                timestamp: p.timestamp,
                avg: p.value,
                min: p.value,
                max: p.value,
            }));
        }
    }

    // 计算各字段的 min/max 用于归一化（基于 avg）
    const fieldRanges = {};
    for (const field of fieldNames) {
        const vals = plotData[field].filter(b => b.avg !== null).map(b => b.avg);
        const fMin = vals.length > 0 ? Math.min(...vals) : 0;
        const fMax = vals.length > 0 ? Math.max(...vals) : 1;
        fieldRanges[field] = { min: fMin, max: fMax, range: (fMax - fMin) || 1 };
    }

    // ---- 左 Y 轴：百分比网格线 ----
    historyCtx.strokeStyle = '#2a2a3e';
    historyCtx.lineWidth = 1;
    const gridCount = 4;
    for (let i = 0; i <= gridCount; i++) {
        const y = pad.top + (chartH * i) / gridCount;
        historyCtx.beginPath();
        historyCtx.moveTo(pad.left, y);
        historyCtx.lineTo(W - pad.right, y);
        historyCtx.stroke();

        const pct = Math.round((1 - i / gridCount) * 100);
        historyCtx.fillStyle = '#8888aa';
        historyCtx.font = '11px sans-serif';
        historyCtx.textAlign = 'right';
        historyCtx.textBaseline = 'middle';
        historyCtx.fillText(pct + '%', pad.left - 8, y);
    }

    // ---- 绘制每条归一化数据线 ---- 
    let colorIdx = 0;
    for (const field of fieldNames) {
        const buckets = plotData[field];
        const color = HISTORY_COLORS[colorIdx % HISTORY_COLORS.length];
        const range = fieldRanges[field];
        colorIdx++;

        // 归一化辅助函数
        const norm = (val) => (val - range.min) / range.range;
        const xPos = (ts) => pad.left + ((ts - tMin) / tRange) * chartW;
        const yPos = (val) => pad.top + chartH - norm(val) * chartH;

        const validBuckets = buckets.filter(b => b.avg !== null);
        if (validBuckets.length === 0) continue;

        if (isAggregate) {
            // 聚合模式：绘制 min-max 范围带 + avg 线
            // 先绘制半透明范围带
            historyCtx.beginPath();
            historyCtx.fillStyle = color + '18';
            let started = false;
            for (const b of validBuckets) {
                if (b.min === null) continue;
                const x = xPos(b.timestamp);
                const yTop = yPos(b.max);
                const yBot = yPos(b.min);
                if (!started) {
                    historyCtx.moveTo(x, yTop);
                    started = true;
                }
                historyCtx.lineTo(x, yTop);
            }
            // 从右到左画底部
            for (let i = validBuckets.length - 1; i >= 0; i--) {
                const b = validBuckets[i];
                if (b.min === null) continue;
                const x = xPos(b.timestamp);
                const yBot = yPos(b.min);
                historyCtx.lineTo(x, yBot);
            }
            historyCtx.closePath();
            historyCtx.fill();

            // 绘制 avg 线
            historyCtx.beginPath();
            historyCtx.strokeStyle = color;
            historyCtx.lineWidth = 1.5;
            historyCtx.lineJoin = 'round';
            historyCtx.lineCap = 'round';
            for (let i = 0; i < validBuckets.length; i++) {
                const b = validBuckets[i];
                const x = xPos(b.timestamp);
                const y = yPos(b.avg);
                if (i === 0) historyCtx.moveTo(x, y);
                else historyCtx.lineTo(x, y);
            }
            historyCtx.stroke();

        } else {
            // 原始模式：直接绘制连线
            historyCtx.beginPath();
            historyCtx.strokeStyle = color;
            historyCtx.lineWidth = 1.5;
            historyCtx.lineJoin = 'round';
            historyCtx.lineCap = 'round';
            for (let i = 0; i < validBuckets.length; i++) {
                const b = validBuckets[i];
                const x = xPos(b.timestamp);
                const y = yPos(b.avg);
                if (i === 0) historyCtx.moveTo(x, y);
                else historyCtx.lineTo(x, y);
            }
            historyCtx.stroke();
        }
    }

    // ---- 右侧图例（含实际值范围）----
    const legendX = W - pad.right + 10;
    fieldNames.forEach((field, idx) => {
        const color = HISTORY_COLORS[idx % HISTORY_COLORS.length];
        const range = fieldRanges[field];

        const ly = pad.top + 5 + (idx + 1) * 22;

        historyCtx.fillStyle = color;
        historyCtx.fillRect(legendX, ly, 14, 3);

        historyCtx.fillStyle = color;
        historyCtx.font = 'bold 12px sans-serif';
        historyCtx.textAlign = 'left';
        historyCtx.textBaseline = 'middle';
        historyCtx.fillText(field, legendX + 18, ly + 2);

        historyCtx.fillStyle = '#8888aa';
        historyCtx.font = '10px sans-serif';
        historyCtx.fillText(
            `${range.min.toFixed(1)} ~ ${range.max.toFixed(1)}`,
            legendX + 18, ly + 16
        );
    });

    // ---- X 轴时间标签 ----
    historyCtx.fillStyle = '#8888aa';
    historyCtx.font = '10px sans-serif';
    historyCtx.textAlign = 'center';
    historyCtx.textBaseline = 'top';

    // 显示5个均匀分布的时间标签
    const labelCount = 5;
    for (let i = 0; i < labelCount; i++) {
        const ts = tMin + (tRange * i) / (labelCount - 1);
        const x = pad.left + ((ts - tMin) / tRange) * chartW;
        const d = new Date(ts);
        const label = d.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
        historyCtx.fillText(label, x, pad.top + chartH + 6);
    }
}

// ================================================================
// Python 刷写
// ================================================================

let flashSelectedFile = null;
let flashIsUploading = false;

const flashDropZone = $('dropZone');
const flashFileInput = $('fileInput');
const flashFileInfo = $('fileInfo');
const flashFileName = $('fileName');
const flashFileSize = $('fileSize');
const flashFileCrc = $('fileCrc');
const flashUploadBtn = $('uploadBtn');
const flashResetBtn = $('resetBtn');
const flashProgressBar = $('flashProgressBar');
const flashProgressFill = $('flashProgressFill');
const flashLogBox = $('logBox');
const flashStatusDot = $('flashStatusDot');
const flashStatusText = $('flashStatusText');

function flashLog(msg, type = 'info') {
    if (!flashLogBox) return;
    const time = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    flashLogBox.innerHTML += `\n[${time}] <span class="${type}">${escapeHtml(msg)}</span>`;
    flashLogBox.scrollTop = flashLogBox.scrollHeight;
}

function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    return (bytes / 1024).toFixed(1) + ' KB';
}

function flashSetStatus(text, state) {
    if (!flashStatusText) return;
    flashStatusText.textContent = text;
    flashStatusDot.className = 'status-dot';
    if (state) flashStatusDot.classList.add(state);
}

function computeCRC32(data) {
    const crcTable = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
        let c = i;
        for (let j = 0; j < 8; j++) {
            c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        }
        crcTable[i] = c;
    }
    let crc = 0xFFFFFFFF;
    for (let i = 0; i < data.length; i++) {
        crc = crcTable[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
    }
    return (crc ^ 0xFFFFFFFF) >>> 0;
}

async function flashCheckDevice() {
    try {
        const resp = await fetch('/api/status');
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const data = await resp.json();
        if (data.data_source === 'active') {
            flashSetStatus('设备已连接', 'connected');
        } else {
            flashSetStatus('设备未连接', 'disconnected');
        }
    } catch (err) {
        flashSetStatus('无法连接服务器', 'disconnected');
    }
}

// 事件绑定
if (flashDropZone) {
    flashDropZone.addEventListener('click', () => flashFileInput?.click());

    flashDropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        flashDropZone.classList.add('dragover');
    });

    flashDropZone.addEventListener('dragleave', () => {
        flashDropZone.classList.remove('dragover');
    });

    flashDropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        flashDropZone.classList.remove('dragover');
        const file = e.dataTransfer.files[0];
        if (file) handleFlashFile(file);
    });
}

if (flashFileInput) {
    flashFileInput.addEventListener('change', () => {
        const file = flashFileInput.files[0];
        if (file) handleFlashFile(file);
    });
}

function handleFlashFile(file) {
    if (!file.name.endsWith('.py')) {
        flashLog('仅支持 .py 文件', 'error');
        return;
    }

    flashSelectedFile = file;
    flashFileName.textContent = file.name;
    flashFileSize.textContent = formatSize(file.size);
    flashDropZone.classList.add('has-file');
    flashFileInfo.style.display = 'block';
    flashUploadBtn.disabled = false;

    const reader = new FileReader();
    reader.onload = (e) => {
        const data = new Uint8Array(e.target.result);
        const crc = computeCRC32(data);
        flashFileCrc.textContent = crc.toString(16).toUpperCase().padStart(8, '0');
        flashLog(`已选择: ${file.name} (${formatSize(file.size)}, CRC32: ${flashFileCrc.textContent})`, 'info');
    };
    reader.readAsArrayBuffer(file);
}

if (flashUploadBtn) {
    flashUploadBtn.addEventListener('click', async () => {
        if (!flashSelectedFile || flashIsUploading) return;
        flashIsUploading = true;
        flashUploadBtn.disabled = true;
        flashResetBtn.disabled = true;
        flashSetStatus('正在上传...', 'uploading');
        flashProgressBar.classList.add('active');

        try {
            const formData = new FormData();
            formData.append('file', flashSelectedFile);

            flashLog('开始上传...', 'info');
            flashProgressFill.style.width = '30%';

            const resp = await fetch('/api/upload', {
                method: 'POST',
                body: formData
            });

            flashProgressFill.style.width = '80%';

            const result = await resp.json();
            if (result.success) {
                flashLog(`上传成功！已写入 ${result.bytes} 字节`, 'success');
                flashLog('设备即将重启...', 'warn');
                flashProgressFill.style.width = '100%';
                flashSetStatus('上传成功', 'connected');
            } else {
                flashLog(`上传失败: ${result.message}`, 'error');
                flashSetStatus('上传失败', 'disconnected');
                flashProgressFill.style.width = '0%';
            }
        } catch (err) {
            flashLog(`上传异常: ${err.message}`, 'error');
            flashSetStatus('上传失败', 'disconnected');
            flashProgressFill.style.width = '0%';
        } finally {
            flashIsUploading = false;
            flashUploadBtn.disabled = false;
            flashResetBtn.disabled = false;
            setTimeout(() => flashProgressBar.classList.remove('active'), 2000);
        }
    });
}

if (flashResetBtn) {
    flashResetBtn.addEventListener('click', async () => {
        flashLog('发送重启命令...', 'info');
        try {
            const resp = await fetch('/api/reset', { method: 'POST' });
            const result = await resp.json();
            if (result.success) {
                flashLog('重启命令已发送', 'success');
            } else {
                flashLog(`重启失败: ${result.message}`, 'error');
            }
        } catch (err) {
            flashLog(`重启异常: ${err.message}`, 'error');
        }
    });
}

// ================================================================
// 初始化历史 tab（当 DOM 加载完成时，如果默认不是历史 tab，则懒初始化）
// ================================================================

// ===== 启动 =====
async function init() {
    // 初始加载
    await pollAll();

    // 每秒轮询
    state.updateInterval = setInterval(pollAll, 1000);
}

document.addEventListener('DOMContentLoaded', init);
