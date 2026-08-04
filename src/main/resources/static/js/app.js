document.addEventListener('alpine:init', () => {
  Alpine.data('chatApp', () => ({
    conversations: [],
    activeId: null,
    messages: [],
    draft: '',
    loading: false,
    uploading: false,
    dragOver: false,
    uploadError: null,
    now: Date.now(),
    _seq: 0,

    init() {
      if (window.marked && typeof marked.setOptions === 'function') {
        marked.setOptions({ breaks: true, gfm: true });
      }
      this.conversations = this.loadConversations();
      if (this.conversations.length) {
        this.activeId = this.conversations[0].id;
      }
      setInterval(() => { this.now = Date.now(); }, 500);
    },

    nextKey() {
      return ++this._seq;
    },

    LS_KEY: 'kg.conversations',

    readMap() {
      try {
        return JSON.parse(localStorage.getItem(this.LS_KEY) || '{}');
      } catch (err) {
        return {};
      }
    },

    writeMap(map) {
      try {
        localStorage.setItem(this.LS_KEY, JSON.stringify(map));
      } catch (err) {
        // localStorage may be full or disabled — non-fatal for the UI
      }
    },

    loadConversations() {
      const map = this.readMap();
      return Object.entries(map)
        .map(([id, value]) => ({
          id: id,
          title: (value && value.title) || '(untitled)',
          createdAt: (value && value.createdAt) || ''
        }))
        .sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
    },

    persistConversation(id, title) {
      const map = this.readMap();
      map[id] = { title: String(title).slice(0, 40), createdAt: new Date().toISOString() };
      this.writeMap(map);
      this.conversations = this.loadConversations();
    },

    removeConversation(id) {
      const map = this.readMap();
      delete map[id];
      this.writeMap(map);
      this.conversations = this.conversations.filter((conv) => conv.id !== id);
    },

    startNewChat() {
      this.activeId = null;
      this.messages = [];
      this.draft = '';
    },

    selectConversation(id) {
      this.activeId = id;
      this.messages = [];
      this.loading = false;
    },

    async deleteConversation(id) {
      if (!confirm('Delete this conversation?')) {
        return;
      }
      try {
        const res = await fetch(
          '/api/knowledge-graph/chat?conversationId=' + encodeURIComponent(id),
          { method: 'DELETE' }
        );
        if (res.status === 204 || res.status === 404) {
          this.removeConversation(id);
          if (this.activeId === id) {
            this.activeId = null;
            this.messages = [];
          }
        } else {
          this.messages.push({
            key: this.nextKey(), role: 'system', type: 'error',
            content: 'Delete failed (HTTP ' + res.status + ').'
          });
        }
      } catch (err) {
        this.messages.push({
          key: this.nextKey(), role: 'system', type: 'error',
          content: 'Network error \u2014 is the server running?'
        });
      }
      this.scrollThread();
    },

    async sendMessage() {
      const prompt = this.draft.trim();
      if (!prompt || this.loading) {
        return;
      }
      const startedNew = !this.activeId;
      this.messages.push({ key: this.nextKey(), role: 'user', content: prompt });
      this.draft = '';
      this.$nextTick(() => {
        const input = this.$refs.input;
        if (input) {
          input.style.height = 'auto';
        }
      });
      this.loading = true;
      const placeholder = {
        key: this.nextKey(), role: 'assistant', content: '',
        pending: true, startedAt: Date.now(), trace: null
      };
      this.messages.push(placeholder);
      this.scrollThread();

      const request = { prompt };
      if (this.activeId) {
        request.conversationId = this.activeId;
      }

      let res, body;
      try {
        res = await fetch('/api/knowledge-graph/chat', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request)
        });
        body = await res.json().catch(() => null);
      } catch (err) {
        this.replaceMessage(placeholder, {
          key: this.nextKey(), role: 'system', type: 'error',
          content: 'Network error \u2014 is the server running?'
        });
        this.loading = false;
        this.scrollThread();
        return;
      }

      if (res.ok) {
        const answer = body && body.answer ? body.answer : '';
        const error = body ? body.error : null;
        if (answer) {
          const replaced = this.replaceMessage(placeholder, {
            key: this.nextKey(), role: 'assistant', content: answer,
            pending: false, trace: this.buildTrace(body)
          });
          if (replaced && startedNew && body && body.conversationId) {
            this.activeId = body.conversationId;
            this.persistConversation(body.conversationId, prompt);
          }
        } else if (error) {
          this.replaceMessage(placeholder, {
            key: this.nextKey(), role: 'system', type: 'error',
            content: 'Chat failed: ' + error
          });
        } else {
          const trace = this.buildTrace(body);
          const hasTrace = trace.cypherQueries.length > 0 || trace.skillsExecuted.length > 0;
          if (hasTrace) {
            const replaced = this.replaceMessage(placeholder, {
              key: this.nextKey(), role: 'assistant',
              content: '_The model returned no answer text. See the trace below for what was queried._',
              pending: false, trace: trace
            });
            if (replaced && startedNew && body && body.conversationId) {
              this.activeId = body.conversationId;
              this.persistConversation(body.conversationId, prompt);
            }
          } else {
            this.replaceMessage(placeholder, {
              key: this.nextKey(), role: 'system', type: 'error',
              content: 'Chat returned no answer.'
            });
          }
        }
      } else {
        this.replaceMessage(placeholder, {
          key: this.nextKey(), role: 'system', type: 'error',
          content: this.chatErrorMessage(res.status, body)
        });
      }
      this.loading = false;
      this.scrollThread();
    },

    buildTrace(body) {
      return {
        cypherQueries: (body && body.cypherQueries) || [],
        skillsExecuted: (body && body.skillsExecuted) || [],
        results: (body && body.results) || [],
        rowCount: (body && body.rowCount) || 0,
        truncated: !!(body && body.truncated),
        error: (body && body.error) || null
      };
    },

    replaceMessage(old, replacement) {
      const idx = this.messages.findIndex((message) => message.key === old.key);
      if (idx < 0) {
        return false;
      }
      this.messages.splice(idx, 1, replacement);
      return true;
    },

    onEnter(event) {
      if (event.shiftKey) {
        return;
      }
      event.preventDefault();
      this.sendMessage();
    },

    handleDrop(event) {
      this.dragOver = false;
      const files = Array.from((event.dataTransfer && event.dataTransfer.files) || []);
      if (files.length === 0) {
        return;
      }
      if (files.length > 1) {
        this.messages.push({
          key: this.nextKey(), role: 'system', type: 'info',
          content: 'Ignoring ' + (files.length - 1) + ' extra file(s) \u2014 only one PDF at a time.'
        });
      }
      this.uploadFile(files[0]);
    },

    handleFileInput(event) {
      const file = event.target.files && event.target.files[0];
      event.target.value = '';
      if (file) {
        this.uploadFile(file);
      }
    },

    async uploadFile(file) {
      if (!file) {
        return;
      }
      const name = file.name || 'file';
      if (!name.toLowerCase().endsWith('.pdf')) {
        this.uploadError = 'Only PDF files are accepted.';
        setTimeout(() => {
          if (this.uploadError) {
            this.uploadError = null;
          }
        }, 3000);
        return;
      }
      this.uploadError = null;
      this.uploading = true;
      const progress = {
        key: this.nextKey(), role: 'system', type: 'progress',
        content: 'Uploading ' + name + '\u2026'
      };
      this.messages.push(progress);
      this.scrollThread();

      const formData = new FormData();
      formData.append('file', file);
      let res, body;
      try {
        res = await fetch('/api/knowledge-graph', { method: 'POST', body: formData });
        body = await res.json().catch(() => null);
      } catch (err) {
        this.replaceMessage(progress, {
          key: this.nextKey(), role: 'system', type: 'error',
          content: 'Upload failed: Network error \u2014 is the server running?'
        });
        this.uploading = false;
        this.scrollThread();
        return;
      }
      this.replaceMessage(progress, this.ingestResultMessage(res, body, name));
      this.uploading = false;
      this.scrollThread();
    },

    ingestResultMessage(res, body, name) {
      if (res.ok) {
        if (body && body.status === 'SKIPPED_DUPLICATE') {
          return {
            key: this.nextKey(), role: 'system', type: 'info',
            content: 'Skipped: ' + name + ' already ingested.'
          };
        }
        return {
          key: this.nextKey(), role: 'system', type: 'info',
          content: this.formatIngestSummary(body, name)
        };
      }
      return {
        key: this.nextKey(), role: 'system', type: 'error',
        content: 'Upload failed: ' + this.uploadErrorMessage(res.status, body)
      };
    },

    formatIngestSummary(body, name) {
      if (!body) {
        return 'Uploaded ' + name + '.';
      }
      const nodes = body.nodeCount || 0;
      if (nodes === 0) {
        return 'Ingested ' + name + ' \u2014 extracted 0 nodes (PDF may be image-only or encrypted).';
      }
      const labels = this.countParts(body.countsByLabel);
      const rels = body.relationshipCount || 0;
      const types = this.countParts(body.countsByType);
      let msg = 'Ingested ' + name + ' \u2014 ' + nodes + ' node' + (nodes === 1 ? '' : 's');
      if (labels) {
        msg += ' (' + labels + ')';
      }
      msg += ', ' + rels + ' relationship' + (rels === 1 ? '' : 's');
      if (types) {
        msg += ' (' + types + ')';
      }
      if (body.failedChunks > 0) {
        msg += ', ' + body.failedChunks + ' chunk' + (body.failedChunks === 1 ? '' : 's') + ' failed';
      }
      return msg;
    },

    countParts(map) {
      if (!map) {
        return '';
      }
      const entries = Object.entries(map).filter(([, value]) => value);
      if (!entries.length) {
        return '';
      }
      return entries.map(([key, value]) => value + ' ' + key).join(', ');
    },

    chatErrorMessage(status, body) {
      if (status === 503) {
        return 'Service unavailable: Neo4j is not reachable.';
      }
      if (body && typeof body === 'object' && 'conversationId' in body) {
        const error = body.error || 'Chat failed.';
        return status === 422 ? 'Chat failed: ' + error : error;
      }
      if (body && body.message) {
        return body.message;
      }
      if (status === 400) {
        return 'Invalid prompt.';
      }
      return 'Chat failed (HTTP ' + status + ').';
    },

    uploadErrorMessage(status, body) {
      if (status === 503) {
        return 'Service unavailable: Neo4j is not reachable.';
      }
      if (status === 413) {
        return (body && body.message) || 'PDF exceeds the page limit or is too large.';
      }
      if (status === 400) {
        return (body && body.message) || 'Invalid file.';
      }
      return (body && body.message) || ('HTTP ' + status);
    },

    hintMessage() {
      if (this.conversations.length === 0) {
        return 'Send a message or drop a PDF to begin.';
      }
      if (this.activeId) {
        return 'Continue conversation \u2014 your history is on the server. Send a message to proceed.';
      }
      return 'New chat \u2014 send a message.';
    },

    messageClass(message) {
      const classes = ['kg-msg', 'kg-' + message.role];
      if (message.type) {
        classes.push('kg-' + message.type);
      }
      return classes.join(' ');
    },

    systemIcon(type) {
      if (type === 'error') {
        return '\u26A0';
      }
      if (type === 'progress') {
        return '\u21BB';
      }
      return '\u2713';
    },

    elapsedFor(message) {
      if (!message.startedAt) {
        return 0;
      }
      return Math.floor(((this.now || Date.now()) - message.startedAt) / 1000);
    },

    renderMarkdown(content) {
      if (!content) {
        return '';
      }
      if (!window.DOMPurify) {
        return window.kgEscape(content);
      }
      const html = (window.marked && typeof marked.parse === 'function')
        ? marked.parse(content)
        : content;
      return DOMPurify.sanitize(html);
    },

    autoGrow(event) {
      const el = event.target || this.$refs.input;
      if (!el) {
        return;
      }
      el.style.height = 'auto';
      el.style.height = Math.min(el.scrollHeight, 200) + 'px';
    },

    scrollThread() {
      this.$nextTick(() => {
        const thread = this.$refs.thread;
        if (thread) {
          thread.scrollTop = thread.scrollHeight;
        }
      });
    }
  }));
});
