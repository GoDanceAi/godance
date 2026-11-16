import { useState } from 'react';
import { Link } from 'react-router-dom';
import { API_BASE } from '../../lib/api';
import { codeRequest, type HandOver } from '../../lib/hand-over';
import {
  CODE_LANGUAGES,
  codeFor,
  JAVA_CLASS,
  uploadCode,
  type AnswerMode,
  type CodeLanguage,
} from '../../lib/run-code';
import CodeFigure from '../CodeFigure';

const MODES: { id: AnswerMode; label: string }[] = [
  { id: 'poll', label: 'Poll a job' },
  { id: 'wait', label: 'Wait for the image' },
];

const LANGUAGE_NOTE: Record<CodeLanguage, string | null> = {
  curl: null,
  python: 'Needs the OpenAI SDK: pip install openai',
  java: `Needs spring-webflux on the classpath (in Spring Boot, spring-boot-starter-webflux). Save it as ${JAVA_CLASS}.java.`,
};

/**
 * "Code": this exact request in curl, Python and Java, all written from the
 * one request object. An image request can ask for a job and read it until
 * it ends, or wait for the image with a synchronous key; a video is always
 * a job. The key is the GODANCE_API_KEY variable in every one of them.
 */
export default function CodeTab({ handOver }: { handOver: HandOver }) {
  const [language, setLanguage] = useState<CodeLanguage>('curl');
  const [mode, setMode] = useState<AnswerMode>('poll');
  const image = handOver.modality === 'IMAGE';
  const snippets = codeFor(language, codeRequest(handOver, mode));
  const note = LANGUAGE_NOTE[language];

  return (
    <div className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <Choice label="Language" options={CODE_LANGUAGES} value={language} onChange={setLanguage} />
        {image && <Choice label="How it answers" options={MODES} value={mode} onChange={setMode} />}
      </div>

      <p className="text-xs text-fg-muted">
        {!image && 'A video is always a job: the code starts it, and reads it until it is done. '}
        {image && mode === 'poll'
          && 'Works with any key: the request asks for a job ("async": true) and reads it until it is done. '}
        {image && mode === 'wait' && (
          <>
            Needs a key set to synchronous image responses: make one for this on the{' '}
            <Link to={`/keys?for=${encodeURIComponent(handOver.name)}`} className="text-brand-600 hover:underline">
              API keys
            </Link>{' '}
            page. The request then waits and answers with the images, as OpenAI's API does.{' '}
          </>
        )}
        The key is read from <code className="code-chip">GODANCE_API_KEY</code>, never written into the code.
      </p>

      {handOver.uploads.length > 0 && (
        <div className="space-y-2">
          <p className="text-xs text-fg-muted">
            This recipe takes {handOver.uploads.length === 1 ? 'an image' : 'images'}. Upload{' '}
            {handOver.uploads.length === 1 ? 'it' : 'each one'} first, then put the id the upload answers with in
            place of{' '}
            {handOver.uploads.map((upload, i) => (
              <span key={upload.placeholder}>
                {i > 0 && (i === handOver.uploads.length - 1 ? ' and ' : ', ')}
                <code className="code-chip">{upload.placeholder}</code> (for “{upload.slot}”)
              </span>
            ))}.
          </p>
          <UploadFigure language={language} fileName={`${handOver.uploads[0].slot}.png`} />
        </div>
      )}

      {snippets.map((snippet) => (
        <CodeFigure key={snippet.title} title={snippet.title} code={snippet.code} note={snippet.note} />
      ))}
      {note && <p className="text-xs text-fg-faint">{note}</p>}
    </div>
  );
}

function UploadFigure({ language, fileName }: { language: CodeLanguage; fileName: string }) {
  const upload = uploadCode(language, API_BASE, fileName);
  return <CodeFigure title={upload.title} code={upload.code} note={upload.note} />;
}

/** One choice among a few, as a row of buttons; the chosen one is pressed. */
function Choice<T extends string>({ label, options, value, onChange }: {
  label: string;
  options: readonly { id: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-2">
      <span className="text-xs font-medium text-fg-subtle">{label}</span>
      <div className="inline-flex flex-wrap rounded-md border border-border bg-surface p-0.5">
        {options.map((option) => (
          <button
            key={option.id}
            type="button"
            aria-pressed={option.id === value}
            onClick={() => onChange(option.id)}
            className={`rounded-sm px-3 py-1 text-xs font-semibold transition-colors ${
              option.id === value ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}
