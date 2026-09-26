/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package graphtheory;

import java.awt.Point;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Scanner;
import java.util.Vector;
import javax.swing.JFileChooser;

/**
 *
 * @author mk
 */
public class FileManager {

    public JFileChooser jF;

    public FileManager() {
        jF = new JFileChooser();


    }

    public void saveFile(Vector<Vertex> vList, File fName) {
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(fName));

            out.write(""+vList.size());
            out.newLine();
            for (Vertex v : vList) {
                out.write(v.name);
                out.newLine();
            }
            for (int i = 0; i < vList.size(); i++) {
                for (int j = 0; j < vList.size(); j++) {
                    if (vList.get(i).connectedToVertex(vList.get(j))) {
                        out.write("1");
                    } else {
                        out.write("0");
                    }
                }
                out.newLine();
            }
            for (int k = 0; k < vList.size(); k++) {
                out.write(vList.get(k).location.x + "," + vList.get(k).location.y);
                out.newLine();
            }
            out.close();

        } catch (IOException e) {
            System.out.println(e);
        }

    }

    public Vector<Vector> loadFile(File fName) {
        Vector<Vertex> vertexList = new Vector<Vertex>();
        Vector<Edge> edgeList = new Vector<Edge>();
        Vector<Vector> file = new Vector<Vector>();
        try {
            FileReader f = new FileReader(fName.toString());
            Scanner data = new Scanner(f);
            if (data.hasNext()) {
                int size = Integer.parseInt(data.nextLine());
                for (int i = 0; i < size; i++) {//vertex only
                    Vertex v = new Vertex(data.nextLine(), 0, 0);
                    vertexList.add(v);
                }

                for (int j = 0; j < vertexList.size(); j++) { // adjacency list
                    String adjacencyLine = data.nextLine();
                    System.out.println(adjacencyLine);
                    for (int k = 0; k < vertexList.size(); k++) {
                        if (adjacencyLine.charAt(k) == '1') {
                            vertexList.get(j).addVertex(vertexList.get(k));
                        }
                    }


                    for (int l = j + 1; l < vertexList.size(); l++) { //edges
                        if (adjacencyLine.charAt(l) == '1') {
                            Edge e = new Edge(vertexList.get(j), vertexList.get(l));
                            edgeList.add(e);
                        }
                    }
                }

                if (data.hasNextLine()) {
                    for (Vertex v : vertexList) {
                        String pos = data.nextLine();
                        v.location = new Point(Integer.parseInt(pos.split(",")[0]), Integer.parseInt(pos.split(",")[1]));
                    }
                }

            }
        } catch (Exception e) {
            System.out.println(e);
        }
        file.add(vertexList);
        file.add(edgeList);
        return file;
    }
}
