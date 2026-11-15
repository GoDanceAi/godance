import { useId, useState } from 'react';
import type { InputRole, MediaModality, ModelInputs } from '../lib/api';
import { useAuth } from '../lib/auth';
import {
  INPUT_ACCEPT,
  inputErrorMessage,
  ROLE_LABEL,
  rolesOf,
  roomFor,
  slotKey,
  uploadInput,
  withNames,
  type FormInput,
  type Slot,
} from '../lib/inputs';
import Dialog from './Dialog';
import { AcceptInputTerms, InputTermsText } from './InputTerms';
import OutputPicker from './OutputPicker';

const GROUP_LABEL: Record<InputRole, { one: string; many: string; what: string }> = {
  image: { one: 'Input image', many: 'Input images', what: 'The image the model works from.' },
  mask: { one: 'Mask', many: 'Mask', what: 'A PNG the size of the first image. Its transparent parts are where the image may change.' },
  first_frame: { one: 'First frame', many: 'First frame', what: 'The image the clip starts from.' },
  last_frame: { one: 'Last frame', many: 'Last frame', what: 'The image the clip ends on. It needs a first frame.' },
  reference_image: { one: 'Reference image', many: 'Reference images', what: 'Images the clip follows. A prompt refers to them in this order.' },
};

function release(input: FormInput | null) {
  if (input?.preview.startsWith('blob:')) URL.revokeObjectURL(input.preview);
}

/**
 * The image inputs of the Studio form: the slots the chosen model takes,
 * each filled by choosing or dropping a file, or by picking one of the
 * user's own images. Shown only while inputs are switched on for the
 * account. The first attempt to add an image before the input terms are
 * accepted opens them, with Accept.
 */
export default function InputSlots({ modality, takes, slots, onChange, disabled = false, onDescribe }: {
  modality: MediaModality;
  takes: ModelInputs | undefined;
  slots: Slot[];
  onChange: (slots: Slot[]) => void;
  disabled?: boolean;
  /** "Describe as prompt" on a filled slot, when the Studio offers it. */
  onDescribe?: (input: FormInput) => void;
}) {
  const { user } = useAuth();
  const [termsOpen, setTermsOpen] = useState(false);
  const [picking, setPicking] = useState<{ role: InputRole; key?: string } | null>(null);
  const [busy, setBusy] = useState<InputRole | null>(null);
  const [errors, setErrors] = useState<Partial<Record<InputRole, string>>>({});
  const baseId = useId();

  const status = user?.inputs;
  if (!status?.enabled) return null;

  const roles = rolesOf(modality).filter((role) => roomFor(takes, role) > 0 || slots.some((slot) => slot.role === role));
  if (roles.length === 0) return null;

  /** Whether the user may add an image now. Opens the terms when they are not accepted yet. */
  const mayAdd = (): boolean => {
    if (status.accepted) return true;
    setTermsOpen(true);
    return false;
  };

  const put = (role: InputRole, input: FormInput, key?: string) => {
    onChange(key
      ? slots.map((slot) => (slot.key === key ? { ...slot, input } : slot))
      : [...slots, { key: slotKey(), role, input }]);
  };

  const remove = (slot: Slot) => {
    release(slot.input);
    // A slot a recipe names stays, empty, with its hint; one the user added goes away.
    onChange(slot.name
      ? slots.map((each) => (each.key === slot.key ? { ...each, input: null } : each))
      : slots.filter((each) => each.key !== slot.key));
  };

  const addFile = async (role: InputRole, file: File | undefined, key?: string) => {
    if (!file || !mayAdd()) return;
    setBusy(role);
    setErrors((current) => ({ ...current, [role]: undefined }));
    try {
      const stored = await uploadInput(file, file.name);
      put(role, { ref: { file_id: stored.id }, preview: URL.createObjectURL(file), label: file.name }, key);
    } catch (err) {
      setErrors((current) => ({ ...current, [role]: inputErrorMessage(err) }));
    } finally {
      setBusy(null);
    }
  };

  return (
    <fieldset className="space-y-4" aria-label="Image inputs" disabled={disabled}>
      {roles.map((role) => {
        const room = roomFor(takes, role);
        const mine = slots.filter((slot) => slot.role === role);
        const filled = mine.filter((slot) => slot.input !== null).length;
        const label = GROUP_LABEL[role];
        const heading = room > 1 ? label.many : label.one;
        const groupId = `${baseId}-${role}`;
        const empty = mine.filter((slot) => slot.input === null);
        // Room for one more: an empty slot a recipe names is filled first.
        const canAdd = room > 0 && filled < room;
        return (
          <div
            key={role}
            data-input-group={role}
            onDragOver={(e) => { if (canAdd) e.preventDefault(); }}
            onDrop={(e) => {
              if (!canAdd) return;
              e.preventDefault();
              void addFile(role, e.dataTransfer.files[0], empty[0]?.key);
            }}
          >
            <p id={groupId} className="text-sm font-medium text-fg">
              {heading}
              {room > 1 && <span className="ml-1.5 font-normal text-fg-subtle">{filled} of up to {room}</span>}
            </p>
            <p className="mt-0.5 text-xs text-fg-subtle">{label.what}</p>
            {room === 0 && (
              <p className="mt-1 text-xs text-warning">The chosen model does not take this input. It is not sent.</p>
            )}

            {mine.length > 0 && (
              <ul aria-labelledby={groupId} className="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-3">
                {mine.map((slot, i) => {
                  const which = slot.name ?? (room > 1 ? `${ROLE_LABEL[role]} ${i + 1}` : ROLE_LABEL[role]);
                  return (
                    <li key={slot.key} data-input-slot={slot.name ?? role} className="embedded-surface p-2">
                      {slot.input ? (
                        <>
                          <div className="relative">
                            {/* A gallery sample that cannot be loaded leaves its slot empty, with the hint. */}
                            <img src={slot.input.preview} alt={`${which}: ${slot.input.label}`}
                                 onError={() => { if (slot.input?.sample) remove(slot); }}
                                 className="aspect-square w-full rounded-sm bg-bg-muted object-cover" />
                            {slot.input.sample && (
                              <span className="pill absolute left-1.5 top-1.5">Sample</span>
                            )}
                          </div>
                          <p className="mt-1.5 truncate text-xs text-fg" title={slot.input.label}>{which}</p>
                          {slot.hint && <p className="text-xs text-fg-subtle">{slot.hint}</p>}
                          <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1">
                            <button type="button" className="btn btn-ghost btn-sm px-0" onClick={() => remove(slot)}
                                    aria-label={`Remove ${which}`}>
                              {slot.input.sample ? 'Replace' : 'Remove'}
                            </button>
                            {onDescribe && role !== 'mask' && (
                              // Named by its own words, then by which image, so "Prompt" still names one field only.
                              <button type="button" className="btn btn-ghost btn-sm px-0" onClick={() => onDescribe(slot.input!)}>
                                Describe as prompt<span className="sr-only">: {which}</span>
                              </button>
                            )}
                          </div>
                        </>
                      ) : (
                        <>
                          <p className="text-xs font-medium text-fg">{which}</p>
                          <p className="mt-0.5 text-xs text-fg-subtle">{slot.hint || 'Add an image for this input.'}</p>
                          {room > 0 && (
                            <AddControls
                              id={`${groupId}-${slot.key}`}
                              which={which}
                              busy={busy === role}
                              offerPicker={role !== 'mask'}
                              onFile={(file) => void addFile(role, file, slot.key)}
                              onPickOutput={() => { if (mayAdd()) setPicking({ role, key: slot.key }); }}
                              guard={mayAdd}
                            />
                          )}
                        </>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}

            {canAdd && empty.length === 0 && (
              <AddControls
                id={`${groupId}-add`}
                which={room > 1 ? `${ROLE_LABEL[role].toLowerCase()} ${filled + 1}` : ROLE_LABEL[role].toLowerCase()}
                busy={busy === role}
                offerPicker={role !== 'mask'}
                onFile={(file) => void addFile(role, file)}
                onPickOutput={() => { if (mayAdd()) setPicking({ role }); }}
                guard={mayAdd}
              />
            )}
            {errors[role] && <p role="alert" className="mt-1.5 text-xs text-danger">{errors[role]}</p>}
          </div>
        );
      })}

      {termsOpen && (
        <Dialog title="Before you add an image" onClose={() => setTermsOpen(false)}>
          <InputTermsText />
          <div className="mt-4">
            <AcceptInputTerms onAccepted={() => setTermsOpen(false)} />
          </div>
        </Dialog>
      )}
      {picking && (
        <OutputPicker
          title={`Use one of your images as the ${ROLE_LABEL[picking.role].toLowerCase()}`}
          onClose={() => setPicking(null)}
          onPick={(input) => {
            put(picking.role, input, picking.key);
            setPicking(null);
          }}
        />
      )}
    </fieldset>
  );
}

/** The two ways to fill a slot: choose a file, or pick one of the user's own images. */
function AddControls({ id, which, busy, offerPicker, onFile, onPickOutput, guard }: {
  id: string;
  which: string;
  busy: boolean;
  offerPicker: boolean;
  onFile: (file: File | undefined) => void;
  onPickOutput: () => void;
  /** False when the user may not add yet (the terms open instead). */
  guard: () => boolean;
}) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2">
      {/* The input itself takes the focus and the keys; the label is what is seen and clicked. */}
      <label htmlFor={id} className="btn btn-secondary btn-sm cursor-pointer focus-within:ring-2 focus-within:ring-brand-500">
        {busy ? 'Uploading…' : 'Choose file'}
        <input
          id={id}
          type="file"
          accept={INPUT_ACCEPT}
          className="sr-only"
          disabled={busy}
          aria-label={`Choose a file for the ${which}`}
          onClick={(e) => { if (!guard()) e.preventDefault(); }}
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = '';   // so the same file can be chosen again after a refusal
            onFile(file);
          }}
        />
      </label>
      {offerPicker && (
        <button type="button" className="btn btn-ghost btn-sm" onClick={onPickOutput} disabled={busy}
                aria-label={`Pick one of your images for the ${which}`}>
          From your images
        </button>
      )}
      <span className="text-xs text-fg-faint">or drop an image here</span>
    </div>
  );
}

/** The longest a slot's name and hint may be, as the API has them. */
const SLOT_NAME_MAX = 40;
const SLOT_HINT_MAX = 200;

/**
 * The name and the hint each input is saved under when the form is kept as a
 * recipe. A recipe keeps the slots, never the images in them. `slots` are
 * the ones the model can use; `all` is the whole list the edit is made in.
 */
export function SlotNames({ slots, all, onChange }: {
  slots: Slot[];
  all: Slot[];
  onChange: (all: Slot[]) => void;
}) {
  const named = withNames(slots).filter((slot) => slot.role !== 'mask');
  const baseId = useId();
  if (named.length === 0) return null;

  const set = (key: string, patch: Partial<Pick<Slot, 'name' | 'hint'>>) =>
    onChange(all.map((slot) => (slot.key === key ? { ...slot, ...patch } : slot)));

  return (
    <fieldset className="mb-4 space-y-3" aria-label="Recipe inputs">
      <p className="text-sm font-medium text-fg">Inputs of the recipe</p>
      <p className="text-xs text-fg-subtle">
        A recipe keeps each input's name and hint, so it can be filled again. It does not keep your images.
      </p>
      {named.map((slot) => (
        <div key={slot.key} className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
          <div>
            <label htmlFor={`${baseId}-${slot.key}-name`} className="block text-xs text-fg-subtle">
              Name of the {ROLE_LABEL[slot.role].toLowerCase()}
            </label>
            <input
              id={`${baseId}-${slot.key}-name`}
              className="input mt-1"
              value={slot.name ?? ''}
              maxLength={SLOT_NAME_MAX}
              onChange={(e) => set(slot.key, { name: e.target.value })}
            />
          </div>
          <div>
            <label htmlFor={`${baseId}-${slot.key}-hint`} className="block text-xs text-fg-subtle">
              Hint: what to put here
            </label>
            <input
              id={`${baseId}-${slot.key}-hint`}
              className="input mt-1"
              value={slot.hint ?? ''}
              maxLength={SLOT_HINT_MAX}
              placeholder="One product on a plain background"
              onChange={(e) => set(slot.key, { hint: e.target.value })}
            />
          </div>
        </div>
      ))}
    </fieldset>
  );
}
