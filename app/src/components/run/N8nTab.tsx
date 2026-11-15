import type { HandOver } from '../../lib/hand-over';
import { N8N_CREDENTIAL, N8N_CREDENTIAL_TYPE, N8N_README, n8nInputsNote, n8nRow, n8nSteps, n8nTemplate } from '../../lib/n8n';

/**
 * "No-code (n8n)": which workflow template fits, where to download it, the
 * four steps that set the key and import it, and what this recipe puts in
 * the sheet. The key goes into an n8n credential, never into the workflow.
 */
export default function N8nTab({ handOver }: { handOver: HandOver }) {
  const template = n8nTemplate(handOver.modality);
  const row = n8nRow(handOver.recipe);
  const inputsNote = n8nInputsNote(handOver.modality, handOver.recipe.inputs);
  return (
    <div className="min-w-0">
      <p className="text-sm text-fg-muted">
        n8n runs a workflow without code. Use the template{' '}
        <span className="break-all font-mono text-[13px] text-fg">{template.file}</span>. {template.does}
      </p>
      <p className="mt-3">
        <a href={template.url} download={template.file} rel="noopener" className="btn btn-secondary btn-sm">
          Download the template
        </a>
      </p>
      <ol aria-label="Set the key and import the template" className="mt-4 list-decimal space-y-2 pl-5 text-sm text-fg-muted">
        {n8nSteps(template).map((step) => <li key={step}>{step}</li>)}
      </ol>

      <h3 className="mt-5 text-xs font-semibold text-fg-subtle">In the sheet, for this recipe</h3>
      <dl aria-label="What a row of the sheet holds" className="mt-2 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1 text-sm">
        {row.map((cell) => (
          <div key={cell.column} className="contents">
            <dt className="font-mono text-[13px] text-fg-subtle">{cell.column}</dt>
            <dd className="break-words text-fg">{cell.value}</dd>
          </div>
        ))}
      </dl>
      {inputsNote && <p className="mt-2 text-sm text-warning">{inputsNote}</p>}
      <p className="mt-3 text-xs text-fg-subtle">
        The credential is a “{N8N_CREDENTIAL_TYPE}” credential named{' '}
        <span className="font-mono text-fg">{N8N_CREDENTIAL}</span>. Every column, and what each node does, is in the{' '}
        <a href={N8N_README} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
          templates' notes
        </a>.
      </p>
    </div>
  );
}
