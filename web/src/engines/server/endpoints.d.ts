// The generated client exists only in the Hilla build (app/src/main/frontend/generated/). In the static build the
// import in load.ts is removed with the dead branch; this declaration lets the type check of web/ pass without it.
declare module 'Frontend/generated/endpoints.js' {
  export const GameEndpoint: unknown;
}
