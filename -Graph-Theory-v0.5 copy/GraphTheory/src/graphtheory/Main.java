package graphtheory;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.Date;

public class Main {

    public static void main(String[] args) throws Exception {

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Date date = new Date();
        new Canvas("Graph Theory SY08-09 Term3 by Team DGLSS v0.5 " + date.toString(), 800, 600, Color.WHITE);

    }
}
