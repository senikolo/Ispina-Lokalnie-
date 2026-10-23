import type { Metadata } from 'next';
import './globals.css';
export const metadata:Metadata={title:'Ispina Most — odjazdy | Ispina Lokalnie',icons:{icon:'/favicon.svg?v=il1',apple:'/favicon.svg?v=il1'},description:'Najbliższe odjazdy A4, A7, A65 i linii gminnej. Oba kierunki, aktualne prognozy KMŁ lub sprawdzone godziny rozkładowe.'};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="pl"><body>{children}</body></html>;}
