import { useMemo } from 'react';
import { ASSISTANT_PROMPT_VERSION } from '../../lib/assistant-prompt';
import { handOverPrompt, type HandOver } from '../../lib/hand-over';
import CodeFigure from '../CodeFigure';

/**
 * "Ask your AI assistant": a prompt to paste into an assistant, which then
 * writes the script. It holds the recipe, where the API is and the rules
 * the script must follow, and nothing else about the account.
 */
export default function AssistantTab({ handOver }: { handOver: HandOver }) {
  const prompt = useMemo(() => handOverPrompt(handOver), [handOver]);
  return (
    <div data-prompt-version={ASSISTANT_PROMPT_VERSION}>
      <p className="text-sm text-fg-muted">
        Paste this into ChatGPT, Claude or the assistant in your editor, and it writes the script. The prompt holds
        this recipe, where the API is and the rules the script must follow. It holds no key and nothing else about
        your account.
      </p>
      <div className="mt-3">
        <CodeFigure title="Prompt for your AI assistant" code={prompt} prose />
      </div>
    </div>
  );
}
