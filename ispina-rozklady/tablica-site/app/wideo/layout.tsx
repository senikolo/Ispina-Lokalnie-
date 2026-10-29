import type {Metadata} from 'next';

export const metadata:Metadata={
  title:'Filmy z Ispiny | Ispina Lokalnie',
  description:'Filmy z Ispiny odtwarzane w jednym odtwarzaczu Ispina Lokalnie.'
};

export default function VideoLayout({children}:{children:React.ReactNode}) {
  return children;
}
