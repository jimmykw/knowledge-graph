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

    init() {
      if (!this.trace) {
        this.trace = { cypherQueries: [], skillsExecuted: [], results: [], rowCount: 0, truncated: false, error: null };
      }
    },

    toggle() {
      this.open = !this.open;
    },

    summary() {
      const rounds = (this.trace.skillsExecuted || []).length;
      const queries = (this.trace.cypherQueries || []).length;
      const rows = this.trace.rowCount || 0;
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
      return (this.trace.results || []).slice(0, 50);
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
