package com.bancoxyz.bff.cajero.sesion;

/** Digito verificador de Luhn: detecta en el cajero un numero mal digitado sin preguntarle al core. */
public final class Luhn {

    private Luhn() {
    }

    public static boolean esValido(String numero) {
        if (numero == null || !numero.matches("\\d{2,19}")) {
            return false;
        }
        int suma = 0;
        boolean doblar = false;
        for (int i = numero.length() - 1; i >= 0; i--) {
            int n = numero.charAt(i) - '0';
            if (doblar) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            suma += n;
            doblar = !doblar;
        }
        return suma % 10 == 0;
    }
}
