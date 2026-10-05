window.kgEscape = function (value) {
  const amp = String.fromCharCode(38);
  const map = {
    '&': amp + 'amp;',
    '<': amp + 'lt;',
    '>': amp + 'gt;',
    '"': amp + 'quot;',
    "'": amp + '#39;'
  };
  return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
    return map[ch];
  });
};

(function registerCypherGrammar() {
  if (!window.hljs || hljs.getLanguage('cypher')) {
    return;
  }
  hljs.registerLanguage('cypher', function () {
    return {
      name: 'Cypher',
      case_insensitive: true,
      keywords: {
        keyword:
          'match merge create delete set remove return where with as optional detach unwind '
          + 'call yield in is not and or xor distinct order by asc desc limit skip union all '
          + 'on unique index constraint reduce foreach use',
        literal: 'true false null'
      },
      contains: [
        { className: 'comment', begin: '//[^\\n]*' },
        { className: 'comment', begin: '/\\*', end: '\\*/' },
        { className: 'string', begin: "'", end: "'", illegal: '\\n' },
        { className: 'string', begin: '"', end: '"', illegal: '\\n' },
        { className: 'string', begin: '`', end: '`' },
        { className: 'number', begin: '\\b\\d+(\\.\\d+)?\\b' },
        { className: 'variable', begin: '\\$[A-Za-z_][A-Za-z0-9_]*' },
        { className: 'type', begin: ':[A-Za-z_][A-Za-z0-9_]*' },
        {
          className: 'built_in',
          beginKeywords:
            'count sum avg min max collect size length type id labels keys properties coalesce '
            + 'tolower toupper trim substring replace split left right toString toBoolean toInteger '
            + 'toFloat head last range nodes relationships startNode endNode point datetime timestamp '
            + 'distance all any none single shortestpath allshortestpaths exists'
        }
      ]
    };
  });
})();

document.addEventListener('alpine:init', () => {
  Alpine.data('tracePanel', (trace) => ({
    trace: trace,
    open: false,
    picked: null,

    init() {
      if (!this.trace) {
        this.trace = { cypherQueries: [], skillsExecuted: [], results: [], evidence: [], rowCount: 0, truncated: false, error: null, route: null, quality: null };
      }
    },

    routeBadge() {
      const route = this.trace.route;
      if (!route) {
        return '';
      }
      if (route.status === 'BLOCKED') {
        const pct = Math.round((1 - (route.graphProbability || 0)) * 100);
        return '\uD83D\uDCAC not from graph \u00B7 ' + route.intent + ' (' + pct + '%)';
      }
      return route.status === 'SKIPPED' ? 'routing skipped' : '';
    },

    qualityChip() {
      const quality = this.trace.quality;
      if (!quality) {
        return '';
      }
      if (quality.status === 'SKIPPED') {
        return 'quality check unavailable';
      }
      return quality.status === 'LOW' ? 'quality low' : 'quality checked';
    },

    qualityIsLow() {
      return !!this.trace.quality && this.trace.quality.status === 'LOW';
    },

    qualityTitle() {
      const quality = this.trace.quality;
      if (!quality || quality.status === 'SKIPPED') {
        return 'The quality judge could not score this answer.';
      }
      const note = quality.evidenceTruncated ? ' Some retrieved rows were not shown to the judge, so a low rating may mean unverified.' : '';
      const detail = quality.status === 'LOW' ? (quality.feedback || 'The judge rated this answer low.') : 'The judge found the answer grounded and relevant.';
      return detail + note;
    },

    toggle() {
      this.open = !this.open;
    },

    evidenceList() {
      return this.trace.evidence || [];
    },

    /** Every Cypher query the agent ran; older responses without evidence fall back to the query strings. */
    queryList() {
      const evidence = this.evidenceList();
      return evidence.length ? evidence.map((entry) => entry.cypher || '') : (this.trace.cypherQueries || []);
    },

    /** Rows across all queries (the response's rowCount only describes the last query). */
    totalRows() {
      const evidence = this.evidenceList();
      return evidence.length ? evidence.reduce((sum, entry) => sum + (entry.count || 0), 0) : (this.trace.rowCount || 0);
    },

    queryMeta(index) {
      const entry = this.evidenceList()[index];
      if (!entry) {
        return '';
      }
      if (entry.error) {
        return 'error: ' + entry.error;
      }
      const count = entry.count || 0;
      return count + (count === 1 ? ' row' : ' rows') + (entry.truncated ? ' \u00B7 server-truncated' : '');
    },

    queryMetaEmpty(index) {
      const entry = this.evidenceList()[index];
      return !!entry && !entry.error && !(entry.count > 0);
    },

    /** The query whose rows are shown: the picked one, else the query with the most rows (the later one on a tie). */
    shownIndex() {
      const evidence = this.evidenceList();
      if (!evidence.length) {
        return -1;
      }
      if (this.picked !== null && this.picked < evidence.length) {
        return this.picked;
      }
      let best = evidence.length - 1;
      for (let index = evidence.length - 1; index >= 0; index--) {
        if ((evidence[index].count || 0) > (evidence[best].count || 0)) {
          best = index;
        }
      }
      return best;
    },

    queriesWithRows() {
      return this.evidenceList().map((entry, index) => ({ index: index, count: entry.count || 0 })).filter((item) => item.count > 0);
    },

    pick(index) {
      this.picked = index;
    },

    shownRows() {
      const index = this.shownIndex();
      return index < 0 ? (this.trace.results || []) : (this.evidenceList()[index].rows || []);
    },

    shownCount() {
      const index = this.shownIndex();
      return index < 0 ? (this.trace.rowCount || 0) : (this.evidenceList()[index].count || 0);
    },

    shownTruncated() {
      const index = this.shownIndex();
      return index < 0 ? !!this.trace.truncated : !!this.evidenceList()[index].truncated;
    },

    summary() {
      const rounds = (this.trace.skillsExecuted || []).length;
      const queries = this.queryList().length;
      const rows = this.totalRows();
      const roundWord = rounds === 1 ? 'round' : 'rounds';
      const rowWord = rows === 1 ? 'row' : 'rows';
      let text = '\uD83D\uDD27 ' + rounds + ' tool ' + roundWord
        + ' \u00B7 ' + queries + ' Cypher queries'
        + ' \u00B7 ' + rows + ' ' + rowWord;
      if (this.trace.error) {
        text += ' \u00B7 \u26A0 error';
      }
      return text;
    },

    cappedRows() {
      return this.shownRows().slice(0, 50);
    },

    columnList() {
      const cols = [];
      for (const row of this.cappedRows()) {
        if (row && typeof row === 'object') {
          for (const key of Object.keys(row)) {
            if (!cols.includes(key)) {
              cols.push(key);
            }
          }
        }
      }
      return cols;
    },

    renderCell(value) {
      if (value === null || value === undefined) {
        return '';
      }
      if (typeof value === 'object') {
        return '<code class="kg-json">' + window.kgEscape(JSON.stringify(value, null, 2)) + '</code>';
      }
      return window.kgEscape(String(value));
    },

    highlightQuery(el, query) {
      if (!window.hljs || typeof query !== 'string') {
        el.textContent = query == null ? '' : query;
        return;
      }
      el.className = 'hljs language-cypher';
      try {
        el.innerHTML = hljs.highlight(query, { language: 'cypher' }).value;
      } catch (err) {
        el.textContent = query;
      }
    },

    copyQuery(query, event) {
      const btn = event.currentTarget;
      const original = btn ? btn.textContent : 'Copy';
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(query).then(() => {
          if (btn) {
            btn.textContent = 'Copied!';
            setTimeout(() => { btn.textContent = original; }, 1200);
          }
        }).catch(() => {});
      }
    }
  }));
});
