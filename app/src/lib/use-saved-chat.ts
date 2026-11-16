import { useEffect, useRef, useState } from 'react';
import { ApiError } from './api';
import { conversations, eligible, loadMessage, reconciledMessage, rejectedWrite,
  type AppendMessage, type ChatMessage, type Conversation, type SavedMessage } from './chat-history';

interface UncertainAppend { localId: string; conversationId: string; known: SavedMessage[]; sent: AppendMessage }
interface SaveProblem { kind: 'rejected' | 'unknown-create' | 'unknown-append' | 'reconciled'; text: string }

/** One mounted account's in-memory transcript and serialized persistence state. */
export function useSavedChat(adult: boolean) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const transcript = useRef<ChatMessage[]>([]);
  const [selected, setSelected] = useState<Conversation | null>(null);
  const selection = useRef<Conversation | null>(null);
  const [list, setList] = useState<Conversation[] | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [listLoading, setListLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [problem, setProblem] = useState<SaveProblem | null>(null);
  const problemRef = useRef<SaveProblem | null>(null);
  const uncertain = useRef<UncertainAppend | null>(null);
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);
  const alive = useRef(true);
  const controller = useRef(new AbortController());
  const listVersion = useRef(0);
  const [archiveUncertain, setArchiveUncertain] = useState(false);

  useEffect(() => {
    alive.current = true;
    if (controller.current.signal.aborted) controller.current = new AbortController();
    return () => { alive.current = false; controller.current.abort(); };
  }, []);
  const update = (next: ChatMessage[] | ((current: ChatMessage[]) => ChatMessage[])) => {
    if (!alive.current) return;
    transcript.current = typeof next === 'function' ? next(transcript.current) : next;
    setMessages(transcript.current);
  };
  const choose = (next: Conversation | null) => {
    selection.current = next;
    if (alive.current) {
      setSelected(next);
      if (next?.archivedAt) {
        ++listVersion.current;
        setListLoading(false);
        setList((items) => items?.filter((item) => item.id !== next.id) ?? null);
      }
    }
  };
  const report = (next: SaveProblem | null) => {
    problemRef.current = next;
    if (alive.current) setProblem(next);
  };
  const begin = () => {
    if (!adult || locked.current || !alive.current) return false;
    locked.current = true;
    setBusy(true);
    return true;
  };
  const end = () => { locked.current = false; if (alive.current) setBusy(false); };

  const loadList = async () => {
    if (!adult || !alive.current) return;
    const version = ++listVersion.current;
    setListOpen(true);
    setListLoading(true);
    setHistoryError(null);
    try {
      const found = await conversations.list(controller.current.signal);
      if (alive.current && version === listVersion.current) setList(found.filter((item) => !item.archivedAt));
    } catch { if (alive.current && version === listVersion.current) setHistoryError('Could not load saved chats. Try loading them again.'); }
    finally { if (alive.current && version === listVersion.current) setListLoading(false); }
  };
  const discardAllowed = () => !transcript.current.some((message) => !message.saved)
    || window.confirm('Leave the current chat? Messages that have not been saved, including partial replies, will leave this page.');

  const newChat = () => {
    if (locked.current || !discardAllowed()) return false;
    choose(null); update([]); report(null); uncertain.current = null; setHistoryError(null); setArchiveUncertain(false);
    return true;
  };
  const open = async (id: string) => {
    if (!adult || locked.current || !discardAllowed() || !begin()) return false;
    setHistoryError(null);
    try {
      const [metadata, saved] = await Promise.all([conversations.get(id, controller.current.signal), conversations.messages(id, controller.current.signal)]);
      if (!alive.current) return false;
      choose(metadata); update(saved.map(loadMessage)); report(null); uncertain.current = null; setArchiveUncertain(false);
      return true;
    } catch (error) {
      if (alive.current) setHistoryError(error instanceof ApiError && error.status === 404
        ? 'This saved chat could not be found for your account. Your current messages are still here.'
        : 'Could not open the saved chat. Your current messages are still here.');
      return false;
    } finally { end(); }
  };

  const checkSelection = async () => {
    const current = selection.current;
    if (!current) return true;
    const metadata = await conversations.get(current.id, controller.current.signal);
    if (!alive.current) return false;
    choose(metadata);
    setArchiveUncertain(false);
    setHistoryError(null);
    if (metadata.archivedAt) {
      setHistoryError('This chat is archived and read-only. Start a new chat to continue.');
      return false;
    }
    return true;
  };
  const prepareSend = async () => {
    if (!selection.current) return true;
    if (!begin()) return false;
    try { return await checkSelection(); }
    catch { if (alive.current) setHistoryError('Could not check whether this saved chat is active. Try again before sending.'); return false; }
    finally { end(); }
  };

  const reconcile = async () => {
    const pending = uncertain.current;
    if (!pending) return;
    try {
      const [metadata, saved] = await Promise.all([
        conversations.get(pending.conversationId, controller.current.signal), conversations.messages(pending.conversationId, controller.current.signal),
      ]);
      if (!alive.current) return;
      choose(metadata);
      const match = reconciledMessage(saved, pending.known, pending.sent);
      if (match) {
        update((current) => current.map((message) => message.localId === pending.localId ? { ...message, saved: match } : message));
        uncertain.current = null;
        report({ kind: 'reconciled', text: 'A matching message was found in the saved copy. Review it before continuing to save. No message was replayed.' });
      } else {
        report({ kind: 'unknown-append', text: 'Save status is unresolved after checking the saved copy. Your answer is still here. No automatic retry was made; check the saved copy again before deciding what to do.' });
      }
    } catch {
      if (alive.current) report({ kind: 'unknown-append', text: 'Save status is unresolved because the saved copy could not be checked. Your answer is still here. No automatic retry was made.' });
    }
  };
  const checkSavedCopy = async () => {
    if (!begin()) return;
    try { await reconcile(); } finally { end(); }
  };

  const persist = async (explicit = true) => {
    if ((!explicit && problemRef.current) || problemRef.current?.kind.startsWith('unknown') || !begin()) return;
    if (!transcript.current.some((message) => eligible(message) && !message.saved)) { report(null); end(); return; }
    report(null);
    try {
      if (!selection.current) {
        const title = transcript.current.find((message) => message.role === 'user')?.content.slice(0, 255) || 'New conversation';
        try {
          const created = await conversations.create(title, controller.current.signal);
          if (!created?.id || typeof created.title !== 'string') throw new Error('Unusable create response');
          if (!alive.current) return;
          choose(created);
          ++listVersion.current;
          setListLoading(false);
          setList((items) => [created, ...(items ?? []).filter((item) => item.id !== created.id)]);
        } catch (error) {
          if (!alive.current) return;
          if (rejectedWrite(error)) report({ kind: 'rejected', text: 'This chat could not be saved. Your answer is still here. Correct the problem, then choose Retry save.' });
          else {
            report({ kind: 'unknown-create', text: 'The create response was not confirmed. Save status is unresolved. Your answer is still here; check Saved chats before starting another save. No automatic retry was made.' });
            await loadList();
          }
          return;
        }
      }
      const id = selection.current!.id;
      // Each append follows the prior response; saved IDs are retained immediately.
      for (const message of transcript.current.filter((item) => eligible(item) && !item.saved)) {
        if (!await checkSelection() || !alive.current) return;
        const sent: AppendMessage = { role: message.role, content: message.content, ...(message.model ? { model: message.model } : {}) };
        const known = transcript.current.flatMap((item) => item.saved ? [item.saved] : []);
        try {
          const saved = await conversations.append(id, sent, controller.current.signal);
          if (!saved?.id || saved.role !== sent.role || saved.content !== sent.content) throw new Error('Unusable append response');
          if (!alive.current) return;
          update((current) => current.map((item) => item.localId === message.localId ? { ...item, saved } : item));
        } catch (error) {
          if (!alive.current) return;
          if (rejectedWrite(error)) report({ kind: 'rejected', text: 'A message could not be saved. Your answer is still here. Correct the problem, then choose Retry save.' });
          else {
            uncertain.current = { localId: message.localId, conversationId: id, known, sent };
            await reconcile();
          }
          return;
        }
      }
    } catch {
      if (alive.current) report({ kind: 'rejected', text: 'The saved chat could not be checked, so no further messages were sent to history. Your answer is still here. Try Retry save when the connection is available.' });
    } finally { end(); }
  };

  const archive = async () => {
    if (!selection.current || locked.current || !window.confirm('Archive this chat? It leaves your active list now and is removed for good after 30 days. Archiving is not immediate erasure. The app cannot restore it.') || !begin()) return;
    const current = selection.current;
    ++listVersion.current;
    setListLoading(false);
    setHistoryError(null);
    try {
      await conversations.archive(current.id, controller.current.signal);
      if (!alive.current) return;
      choose({ ...current, archivedAt: new Date().toISOString() });
      setList((items) => items?.filter((item) => item.id !== current.id) ?? null);
      report(null);
    } catch { if (alive.current) setHistoryError('Could not confirm archival. This chat is read-only until its status is checked.');
      if (alive.current) setArchiveUncertain(true);
    } finally { end(); }
  };

  return { messages, transcript, update, selected, list, listOpen, listLoading, historyError, problem, busy,
    loadList, open, newChat, persist, archive, prepareSend, checkSavedCopy,
    archiveUncertain, readOnly: selected?.archivedAt != null || archiveUncertain };
}
