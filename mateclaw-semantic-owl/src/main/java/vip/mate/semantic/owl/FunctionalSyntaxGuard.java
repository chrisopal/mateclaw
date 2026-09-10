package vip.mate.semantic.owl;

/** Masks data tokens before checking forbidden document-level constructs in an axiom. */
final class FunctionalSyntaxGuard {
    private FunctionalSyntaxGuard() {}
    static String structure(String text) {
        StringBuilder result=new StringBuilder(text.length());
        boolean quoted=false,iri=false,comment=false,escaped=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(comment) {
                if(c=='\n'||c=='\r'){comment=false;result.append(c);}else result.append(' ');
            }else if(quoted) {
                result.append(' ');
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"')quoted=false;
            }else if(iri) {
                result.append(' ');if(c=='>')iri=false;
            }else if(c=='"'){quoted=true;result.append(' ');}
            else if(c=='<'){iri=true;result.append(' ');}
            else if(c=='#'){comment=true;result.append(' ');}
            else result.append(c);
        }
        return result.toString();
    }
}
