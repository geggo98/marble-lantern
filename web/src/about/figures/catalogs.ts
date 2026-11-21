// The catalogs of topics 3 and 4, made once so that the topic pages and their figures share them (a figure may not import its topic
// page: the page imports the figure).
import inferenceEn from '../i18n/en/inference.json';
import inferenceDe from '../i18n/de/inference.json';
import trainingEn from '../i18n/en/training.json';
import trainingDe from '../i18n/de/training.json';
import { makeAboutCatalog } from '../i18n/catalog';

export const inferenceCatalog = makeAboutCatalog({ en: inferenceEn, de: inferenceDe });
export const trainingCatalog = makeAboutCatalog({ en: trainingEn, de: trainingDe });
