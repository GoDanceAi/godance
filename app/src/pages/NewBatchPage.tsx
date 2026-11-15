import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ApiError,
  type BatchRequest,
  type InputRef,
  type ModelListResponse,
  type UserRecipe,
} from '../lib/api';
import { useAuth } from '../lib/auth';
import {
  DEFAULT_CONCURRENCY,
  DEFAULT_MAX_CONCURRENCY,
  DEFAULT_MAX_ROWS,
  defaultPlans,
  effectiveMapping,
  fitParams,
  matchSlot,
  requestRows,
  rowNumber,
  rowValues,
  tooLong,
  type ParamChange,
  type SlotPlan,
} from '../lib/batch-table';
import { fileKey, useUploader } from '../lib/batch-uploads';
import { badRow, createBatch, knownLimits, rowReason, useBatchEstimate } from '../lib/batches';
import { parseTable } from '../lib/csv';
import { controlsFor, groupByProvider, loadCatalog, mediaModels, modelName } from '../lib/media-models';
import { sendError } from '../lib/send-error';
import { parseTemplate, renderTemplate, VALUE_MAX_LENGTH } from '../lib/template';
import { fetchRecipe, isRecipeId, paramsText } from '../lib/user-recipes';
import Dialog from '../components/Dialog';
import { AcceptInputTerms, InputTermsText } from '../components/InputTerms';
import SendErrorNotice from '../components/SendErrorNotice';
import BatchPreview, { type PreviewRow } from '../components/batch/BatchPreview';
import BatchStart from '../components/batch/BatchStart';
import ColumnMapping from '../components/batch/ColumnMapping';
import RowsSource from '../components/batch/RowsSource';
import RowsTable, { type TableRow } from '../components/batch/RowsTable';
import SlotImages from '../components/batch/SlotImages';

/** How many rows the preview renders. */
const PREVIEW_ROWS = 3;

type Load = { recipe: UserRecipe } | { missing: true } | { failed: string };

/**
 * /recipes/:id/batch — one of the user's recipes run over a table: rows
 * from a paste or a CSV file, a column for each variable, an image for each
 * input slot, and the total before anything starts.
 */
export default function NewBatchPage() {
  const { recipeId = '' } = useParams();
  const [load, setLoad] = useState<Load | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoad(null);
    if (!isRecipeId(recipeId)) {
      setLoad({ missing: true });
      return;
    }
    fetchRecipe(recipeId)
      .then((recipe) => { if (!cancelled) setLoad({ recipe }); })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad(err instanceof ApiError && err.status === 404
          ? { missing: true }
          : { failed: err instanceof ApiError ? err.detail || err.message : 'Could not load this recipe.' });
      });
    return () => { cancelled = true; };
  }, [recipeId]);

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      {isRecipeId(recipeId)
        ? <Link to={`/recipes/${recipeId}`} className="text-sm text-fg-muted hover:text-fg">← Back to the recipe</Link>
        : <Link to="/recipes" className="text-sm text-fg-muted hover:text-fg">← Recipes</Link>}
      {load === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
      {load && 'missing' in load && (
        <div className="mt-6 card py-10 text-center">
          <p className="font-semibold text-fg">Recipe not found.</p>
          <p className="mt-1 text-sm text-fg-muted">It may have been deleted, or the link is incomplete.</p>
        </div>
      )}
      {load && 'failed' in load && <p role="alert" className="mt-6 text-sm text-danger">{load.failed}</p>}
      {load && 'recipe' in load && <NewBatch key={load.recipe.id} recipe={load.recipe} />}
    </div>
  );
}

/** What another model does with a setting of the version, as a sentence. */
function changeNote(change: ParamChange, model: string): string {
  if (change.key === 'size') return `${change.from} is not a size ${model} offers, so its own default is used.`;
  if (change.key === 'seconds') return `${change.from} seconds is not a length ${model} offers, so its own default is used.`;
  return `${model} makes ${change.to === 1 ? 'one' : `up to ${change.to}`} per request, so each row asks for `
    + `${change.to === 1 ? 'one' : change.to}, not ${change.from}.`;
}

function NewBatch({ recipe }: { recipe: UserRecipe }) {
  const { user, refresh } = useAuth();
  const navigate = useNavigate();
  const version = recipe.latest_version;
  const variables = version.variables;
  const slots = useMemo(() => version.inputs ?? [], [version]);
  const inputsOn = user?.inputs?.enabled === true;
  const renders = useMemo(() => renderTemplate(version.template, variables) !== null, [version, variables]);

  // ----- The table, and which column each variable reads -----
  const [source, setSource] = useState<{ text: string; note: string | null }>({ text: '', note: null });
  const table = useMemo(() => parseTable(source.text), [source.text]);
  const hasTable = table.header.length > 0;
  // A choice made by hand holds for the table it was made on: new columns are matched afresh.
  const signature = table.header.join('\u0000');
  const [chosen, setChosen] = useState<{ signature: string; columns: Record<string, number | null> }>(
    { signature: '', columns: {} });
  const mapping = useMemo(
    () => effectiveMapping(table.header, variables, chosen.signature === signature ? chosen.columns : {}),
    [table.header, variables, chosen, signature]);

  // ----- The images, and which row each goes to -----
  const [files, setFiles] = useState<Record<string, File[]>>({});
  const [chosenPlans, setChosenPlans] = useState<{ signature: string; plans: Record<string, SlotPlan> }>(
    { signature: '', plans: {} });
  const plans = useMemo(
    () => ({ ...defaultPlans(table, slots), ...(chosenPlans.signature === signature ? chosenPlans.plans : {}) }),
    [table, slots, chosenPlans, signature]);
  // With no table, each image is a row.
  const rowCount = hasTable
    ? table.rows.length
    : slots.reduce((most, slot) => Math.max(most, files[slot.slot]?.length ?? 0), 0);
  const assignment = useMemo(() => {
    const out: Record<string, (File | null)[]> = {};
    for (const slot of slots) {
      const mine = files[slot.slot] ?? [];
      const plan: SlotPlan = hasTable ? plans[slot.slot] ?? { by: 'order' } : { by: 'order' };
      out[slot.slot] = matchSlot(table, rowCount, plan, mine.map((file) => file.name))
        .map((at) => (at === null ? null : mine[at]));
    }
    return out;
  }, [slots, files, plans, table, rowCount, hasTable]);
  const needed = useMemo(() => {
    const seen = new Set<string>();
    const list: File[] = [];
    for (const slot of slots) {
      for (const file of assignment[slot.slot]) {
        if (file && !seen.has(fileKey(file))) {
          seen.add(fileKey(file));
          list.push(file);
        }
      }
    }
    return list;
  }, [slots, assignment]);

  // ----- The API's limits, as far as it has said them -----
  const [limits, setLimits] = useState(knownLimits);
  const maxRows = limits?.max_rows ?? null;
  const tooMany = maxRows !== null && rowCount > maxRows;
  // Images are not uploaded for a table that is, or is likely, too long to run.
  const held = slots.length > 0 && rowCount > (maxRows ?? DEFAULT_MAX_ROWS);
  const cap = limits?.max_concurrency ?? DEFAULT_MAX_CONCURRENCY;
  const [wantedConcurrency, setWantedConcurrency] = useState(DEFAULT_CONCURRENCY);
  const concurrency = Math.max(1, Math.min(wantedConcurrency, cap));

  // ----- Uploads -----
  const uploader = useUploader();
  const { want } = uploader;
  useEffect(() => {
    want(inputsOn && !held ? needed : []);
  }, [want, needed, inputsOn, held]);
  // Each needed file's id, or null while any of them is not stored yet.
  const stored = useMemo(() => {
    const ids = new Map<string, string>();
    for (const file of needed) {
      const upload = uploader.of(file);
      if (upload?.status !== 'done') return null;
      ids.set(fileKey(file), upload.fileId);
    }
    return ids;
    // `version` is what says the uploads changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needed, uploader.of, uploader.version]);

  // ----- What is wrong with a row before anything is sent -----
  const problems = useMemo(() => {
    const found = new Map<number, string[]>();
    for (let i = 0; i < rowCount; i += 1) {
      const list: string[] = [];
      const long = table.rows[i] ? tooLong(rowValues(table.rows[i], mapping)) : null;
      if (long) list.push(`The value for ${long} is longer than ${VALUE_MAX_LENGTH.toLocaleString('en-US')} characters.`);
      for (const slot of slots) {
        if (!assignment[slot.slot][i]) list.push(`No image for “${slot.slot}”.`);
      }
      if (list.length > 0) found.set(i, list);
    }
    return found;
  }, [rowCount, table, mapping, slots, assignment]);
  const flagged = useMemo(() => [...problems.keys()], [problems]);

  // ----- The model and the settings -----
  const [catalog, setCatalog] = useState<ModelListResponse | 'failed' | null>(null);
  useEffect(() => {
    let cancelled = false;
    loadCatalog().then(
      (list) => { if (!cancelled) setCatalog(list); },
      () => { if (!cancelled) setCatalog('failed'); },
    );
    return () => { cancelled = true; };
  }, []);
  const models = useMemo(
    () => (catalog && catalog !== 'failed' ? mediaModels(catalog, recipe.modality) : null),
    [catalog, recipe.modality]);
  const groups = useMemo(() => (models ? groupByProvider(models) : null), [models]);
  const [model, setModel] = useState(version.model);
  const entry = models?.find((m) => m.id === model);
  const fitted = useMemo(
    () => fitParams(version.params ?? {}, model === version.model, models ? controlsFor(entry, recipe.modality) : null),
    [version, model, models, entry, recipe.modality]);

  // ----- The request, once every row is whole -----
  const body = useMemo<BatchRequest | null>(() => {
    if (rowCount === 0 || !renders || tooMany || held || problems.size > 0 || stored === null) return null;
    if (slots.length > 0 && !inputsOn) return null;
    const inputs: Record<string, (InputRef | null)[]> = {};
    for (const slot of slots) {
      inputs[slot.slot] = assignment[slot.slot].map((file) => {
        const id = file ? stored.get(fileKey(file)) : undefined;
        return id ? { file_id: id } : null;
      });
    }
    return {
      version_id: version.id,
      model,
      params: fitted.params,
      rows: requestRows(table, rowCount, mapping, inputs),
      max_concurrency: concurrency,
    };
  }, [rowCount, renders, tooMany, held, problems, stored, slots, inputsOn, assignment, version.id, model, fitted, table,
    mapping, concurrency]);

  const estimate = useBatchEstimate(body);
  const answered = estimate.estimate;
  useEffect(() => {
    const said = knownLimits();
    if (answered && said) setLimits(said);
  }, [answered]);

  // ----- Start -----
  const [starting, setStarting] = useState(false);
  const startingNow = useRef(false);
  const [startFailure, setStartFailure] = useState<{ key: string; error: unknown } | null>(null);
  const start = async () => {
    if (!body || !answered || !estimate.key || startingNow.current) return;
    const key = estimate.key;
    startingNow.current = true;
    setStarting(true);
    setStartFailure(null);
    try {
      const batch = await createBatch(body);
      void refresh();
      navigate(`/batches/${batch.id}`);
    } catch (error) {
      setStartFailure({ key, error });
      setStarting(false);
      startingNow.current = false;
    }
  };

  // A refusal belongs to the request it was given for: change the batch and it is gone.
  const failure: unknown = estimate.error ?? (startFailure && startFailure.key === estimate.key ? startFailure.error : null);
  const refusedRow = useMemo(() => {
    const row = badRow(failure);
    return row === null ? null : { row, reason: rowReason(failure, 'The API refused this row.') };
  }, [failure]);

  // ----- The input terms, before the first image -----
  const [termsOpen, setTermsOpen] = useState(false);
  const accepted = user?.inputs?.accepted === true;
  const mayAdd = useCallback(() => {
    if (accepted) return true;
    setTermsOpen(true);
    return false;
  }, [accepted]);

  const rowAt = (index: number): TableRow => {
    const rowFiles = slots.map((slot) => assignment[slot.slot][index] ?? null);
    const list = [...(problems.get(index) ?? [])];
    for (const file of rowFiles) {
      const upload = file ? uploader.of(file) : undefined;
      if (upload?.status === 'failed') list.push(`${file?.name} could not be uploaded.`);
    }
    return {
      values: table.rows[index] ? rowValues(table.rows[index], mapping) : {},
      files: rowFiles,
      problems: list,
    };
  };

  const preview: PreviewRow[] = Array.from({ length: Math.min(PREVIEW_ROWS, rowCount) }, (_, index) => ({
    index,
    prompt: renderTemplate(version.template, variables, table.rows[index] ? rowValues(table.rows[index], mapping) : {}),
    images: slots.map((slot) => ({ slot: slot.slot, file: assignment[slot.slot][index] ?? null })),
  }));

  const usedColumns = new Set<number>();
  for (const column of Object.values(mapping)) if (column !== null) usedColumns.add(column);
  for (const slot of slots) {
    const plan = plans[slot.slot];
    if (plan?.by === 'name') usedColumns.add(plan.column);
  }
  const unused = table.header.map((_, at) => at).filter((at) => !usedColumns.has(at));

  const failedUploads = uploader.counts.failed;
  const waiting = rowCount === 0
    ? (hasTable ? 'Add at least one row under the column names to see the total.'
      : slots.length > 0 ? 'Paste a table, or choose the images, to see the total.'
      : 'Paste a table or choose a CSV file to see the total.')
    : !renders ? "This recipe's template can't be rendered, so it can't be run as a batch."
    : tooMany ? `A batch takes up to ${maxRows?.toLocaleString('en-US')} rows and this table has `
      + `${rowCount.toLocaleString('en-US')}. Remove ${(rowCount - (maxRows ?? 0)).toLocaleString('en-US')}, or run it as more than one batch.`
    : held ? `This table has ${rowCount.toLocaleString('en-US')} rows, more than a batch usually takes. Shorten it to see the total.`
    : problems.size > 0 ? `${problems.size === 1 ? `Row ${rowNumber(flagged[0])} has` : `${problems.size.toLocaleString('en-US')} rows have`} `
      + 'a problem. Fix it in the table to see the total.'
    : stored === null ? (failedUploads > 0
      ? 'Some images could not be uploaded. Try the upload again to see the total.'
      : 'The total is worked out once the images are uploaded.')
    : null;

  const modelLabel = entry ? modelName(entry) : model;
  const notes = fitted.changes.map((change) => changeNote(change, modelLabel));
  const refusal = failure && !refusedRow ? sendError(failure, 'The batch could not be priced. Try again.') : null;
  const problem = refusedRow ? (
    <p className="mt-3 text-sm text-danger">
      Row {rowNumber(refusedRow.row)} was refused, so nothing was started. It is marked in the table above.
    </p>
  ) : refusal ? (
    <div className="mt-3">
      <SendErrorNotice error={refusal} what="this batch" then="to run it" />
      {estimate.error !== null && (
        <button type="button" className="btn btn-secondary btn-sm mt-2" onClick={estimate.retry}>
          Work out the total again
        </button>
      )}
    </div>
  ) : null;

  return (
    <article className="mt-4">
      <p className="text-xs font-semibold uppercase tracking-wide text-fg-subtle">New batch</p>
      <h1 className="mt-1 break-words text-2xl font-bold tracking-tight text-fg">{recipe.title}</h1>
      <p className="mt-1 text-sm text-fg-subtle">
        {recipe.modality === 'VIDEO' ? 'Video' : 'Image'} recipe
        <span aria-hidden="true"> · </span>version {version.number}, the latest
        <span aria-hidden="true"> · </span><span className="break-all font-mono text-[12px]">{version.model}</span>
      </p>
      <p className="mt-4 whitespace-pre-wrap break-words embedded-surface p-4 text-sm text-fg" data-batch-template>
        {parseTemplate(version.template).parts.map((part, i) => ('text' in part
          ? <span key={i}>{part.text}</span>
          : <mark key={i} className="code-chip text-fg">{`{{${part.name}}}`}</mark>))}
      </p>

      {slots.length > 0 && !inputsOn ? (
        <div className="mt-6 card">
          <p className="font-semibold text-fg">This recipe can't be run as a batch from this account right now.</p>
          <p className="mt-1 text-sm text-fg-muted">
            It takes an image for every row, and image inputs are not available for this account.
          </p>
        </div>
      ) : (
        <div className="mt-8 space-y-8">
          <RowsSource
            text={source.text}
            table={table}
            note={source.note}
            onChange={(text, note) => setSource({ text, note })}
          />
          {hasTable && (
            <ColumnMapping
              header={table.header}
              variables={variables}
              mapping={mapping}
              unused={unused}
              onChoose={(variable, column) => setChosen((current) => ({
                signature,
                columns: { ...(current.signature === signature ? current.columns : {}), [variable]: column },
              }))}
            />
          )}
          {slots.length > 0 && (
            <SlotImages
              slots={slots}
              header={table.header}
              hasTable={hasTable}
              rowCount={rowCount}
              files={files}
              plans={plans}
              assignment={assignment}
              needed={needed}
              uploader={uploader}
              held={held ? 'Images are uploaded once the table is short enough to run.' : null}
              mayAdd={mayAdd}
              onAdd={(slot, added) => setFiles((current) => {
                // A file chosen again under the same name takes the place of the earlier one.
                const byName = new Map((current[slot] ?? []).map((file) => [file.name.toLowerCase(), file]));
                for (const file of added) byName.set(file.name.toLowerCase(), file);
                return { ...current, [slot]: [...byName.values()] };
              })}
              onClear={(slot) => setFiles((current) => ({ ...current, [slot]: [] }))}
              onPlan={(slot, plan) => setChosenPlans((current) => ({
                signature,
                plans: { ...(current.signature === signature ? current.plans : {}), [slot]: plan },
              }))}
            />
          )}
          {rowCount > 0 && renders && <BatchPreview rows={preview} total={rowCount} />}
          {rowCount > 0 && (
            <RowsTable
              variables={variables}
              slots={slots}
              rowCount={rowCount}
              rowAt={rowAt}
              uploadOf={uploader.of}
              flagged={flagged}
              error={refusedRow}
            />
          )}
          <BatchStart
            groups={groups}
            catalogFailed={catalog === 'failed'}
            model={model}
            versionModel={version.model}
            onModel={setModel}
            settings={paramsText(fitted.params)}
            notes={notes}
            cap={cap}
            concurrency={concurrency}
            onConcurrency={setWantedConcurrency}
            waiting={waiting}
            estimate={answered}
            asking={estimate.asking}
            problem={problem}
            starting={starting}
            onStart={() => void start()}
          />
        </div>
      )}

      {termsOpen && (
        <Dialog title="Before you add an image" onClose={() => setTermsOpen(false)}>
          <InputTermsText />
          <div className="mt-4">
            <AcceptInputTerms onAccepted={() => setTermsOpen(false)} />
          </div>
        </Dialog>
      )}
    </article>
  );
}
