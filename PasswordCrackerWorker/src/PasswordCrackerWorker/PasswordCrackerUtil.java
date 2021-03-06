package PasswordCrackerWorker;

import org.apache.thrift.TException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static PasswordCrackerWorker.PasswordCrackerConts.PASSWORD_CHARS;
import static PasswordCrackerWorker.PasswordCrackerConts.PASSWORD_LEN;

public class PasswordCrackerUtil {

    private static MessageDigest getMessageDigest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new RuntimeException("Cannot use MD5 Library:" + e.getMessage());
        }
    }

    private static String encrypt(String password, MessageDigest messageDigest) {
        messageDigest.update(password.getBytes());
        byte[] hashedValue = messageDigest.digest();
        return byteToHexString(hashedValue);
    }

    private static String byteToHexString(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    /*
     * The findPasswordInRange method finds the password.
     * if it finds the password, it set the termination for transferring signal to master and returns password to caller.
     */
    public static String findPasswordInRange(long rangeBegin, long rangeEnd, String encryptedPassword, TerminationChecker terminationChecker) throws TException, InterruptedException {
        char passwdFirstChar = encryptedPassword.charAt(0);    // Our little optimization
        int[] arrayKey = new int[PASSWORD_LEN];  // The array which holds each alpha-num item
        String passwd = null;
        
        // Compute first array
        long longKey = rangeBegin;
        transformDecToBase36(longKey, arrayKey);

        for (; longKey < rangeEnd && !terminationChecker.isTerminated(); longKey++) {
            String rawKey = transformIntoStr(arrayKey);
            String md5Key = encrypt(rawKey, getMessageDigest());

            // Avoid full string comparison
            if (md5Key.charAt(0) == passwdFirstChar) {
                if (encryptedPassword.equals(md5Key)) {
                    passwd = rawKey;
					break;
                }
            }
            getNextCandidate(arrayKey);
        }

        return passwd; 
    }

    /* ###  transformDecToBase36  ###
     * The transformDecToBase36 transforms decimal into numArray that is base 36 number system
     * If you don't understand, refer to the homework01 overview
    */
    private static void transformDecToBase36(long numInDec, int[] numArrayInBase36) {
        long quotient = numInDec; 
        int passwdlength = numArrayInBase36.length - 1;

        for (int i = passwdlength; quotient > 0l; i--) {
            int reminder = (int) (quotient % 36l);
            quotient /= 36l;
            numArrayInBase36[i] = reminder;
        }
    }

    //  ### getNextCandidate ###
    private static void getNextCandidate(int[] candidateChars) {
        int i = candidateChars.length - 1;

        while(i >= 0) {
            candidateChars[i] += 1;

            if (candidateChars[i] >= 36) {
                candidateChars[i] = 0;
                i--;

            } else {
                break;
            }
        }
    }

    private static String transformIntoStr(int[] chars) {
        char[] password = new char[chars.length];
        for (int i = 0; i < password.length; i++) {
            password[i] = PASSWORD_CHARS.charAt(chars[i]);
        }
        return new String(password);
    }
}
