// Numbers as the docs write them in the transcribed data files.

/** "1,760" or "60,000" as the docs write it; "126 to 163" is not a number and gives NaN. */
export const parseDocNumber = (text: string): number => Number(text.replace(/,/g, ''));

/** "+338" or "-12" as the docs write Elo values. */
export const parseSigned = (text: string): number => Number(text.replace('−', '-').replace(/^\+/, ''));
