// ============================================================================
//  DRAFT — THE INPUT TERMS.
//  OPERATOR: THIS TEXT IS A DRAFT. READ IT, EDIT IT WITH WHOEVER ADVISES YOU ON
//  PRIVACY, AND ONLY THEN SWITCH INPUTS ON (godance.inputs.enabled).
// ============================================================================
//
// This is the one place the input terms live. A user must accept them before
// uploading an image to use in a generation; Settings shows them, and so does
// the dialog that opens the first time someone adds an input in the Studio.
//
// When you change the text in a way people must agree to again:
//   1. raise `version` here, and
//   2. raise godance.inputs.terms-version (INPUTS_TERMS_VERSION) on the API to
//      the same number, in the same release.
// The API only accepts the current version, so everyone is asked again. While
// the two numbers differ, the app shows no Accept button and asks for a reload.
//
// Keep these in step with what the product really does:
//   - "after 24 hours" is godance.inputs.unused-ttl-hours;
//   - the privacy policy must say the same things about storage, the automatic
//     check, the provider the image is sent to, and deletion.

export interface InputTerms {
  /** Must equal godance.inputs.terms-version on the API. */
  version: number;
  title: string;
  intro: string;
  /** What the user confirms by accepting. */
  confirmations: string[];
  /** What Godance does with an upload. Information, not a promise the user makes. */
  handlingTitle: string;
  handling: string[];
}

export const INPUT_TERMS: InputTerms = {
  version: 1,
  title: 'Using your own images',
  intro: 'You can upload images to use in a generation. By accepting, you confirm that for every image you upload:',
  confirmations: [
    'You have the right to use it: it is yours, or its owner has given you permission.',
    'If it shows a person, that person is you, or has agreed to you using it this way.',
    'It shows no one under 18.',
  ],
  handlingTitle: 'What happens to an image you upload',
  handling: [
    'It is stored privately. Only you can see it, and it never appears in the gallery.',
    'Details stored inside the file, such as where and when a photo was taken, are removed before it is stored.',
    'It may be checked automatically by an AI model, for example to see whether it shows a person.',
    'When you use it in a generation, it is sent to the provider of the model you chose.',
    'It is deleted when the generation that used it is deleted, or after 24 hours if you never use it. You can delete it yourself at any time.',
  ],
};
