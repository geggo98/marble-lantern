// MathML core elements that the Info markup renders ({sci:…}); React's JSX typings do not know them.
import 'react';

declare module 'react' {
  namespace JSX {
    interface IntrinsicElements {
      math: React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>;
      mn: React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>;
      mo: React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>;
      mrow: React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>;
      msup: React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement>;
    }
  }
}
