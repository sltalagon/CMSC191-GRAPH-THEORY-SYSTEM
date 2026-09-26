/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package graphtheory;

import java.util.ArrayList;
import java.util.Vector;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author mk
 */
public class VertexPair {

    public Vertex vertex1;
    public Vertex vertex2;
    public Vector<Vector<Vertex>> pathList;     //all paths
    public Vector<Vector<Vector<Vertex>>> VertexDisjointContainer = new Vector<Vector<Vector<Vertex>>>(); // container of vertex-disjoint sets

    public VertexPair(Vertex v1, Vertex v2) {
        vertex1 = v1;
        vertex2 = v2;
    }


    public int getShortestDistance() {
        //simple BFS
        Vector<Vertex> visitedNodes = new Vector<Vertex>();
        visitedNodes.add(vertex1);      //root node = vertex1

        int counter = 0;
        while (!visitedNodes.contains(vertex2)) {

            int workingSize = visitedNodes.size();
            for (int i = counter; i < workingSize; i++) {
                for (Vertex x : visitedNodes.get(i).connectedVertices) {
                    if (!visitedNodes.contains(x)) {
                        visitedNodes.add(x);
                    }
                }
            }
            counter++;
            if (workingSize == visitedNodes.size()) // if list not growing, pair is disconnected
            {
                return -1;
            }
        }

        return counter;

    }

    public void generateVertexDisjointPaths() {
        VertexDisjointContainer.removeAllElements();
        generatePaths();
        Vector<Vector<Vertex>> tempPathList;

        for (int i = 0; i < pathList.size(); i++) {
            tempPathList = new Vector<Vector<Vertex>>();
            tempPathList.add(pathList.get(i));
            for (int j = 0; j < pathList.size(); j++) {
                if (i != j) {
                    int disjointCount = 0;
                    for (int k = 0; k < tempPathList.size(); k++) {
                        if (areDisjointPaths(pathList.get(j), tempPathList.get(k))) {
                            disjointCount++;
                        }
                    }
                    if (disjointCount == tempPathList.size()) {
                        tempPathList.add(pathList.get(j));
                    }
                }
            }
            if(!isAlreadyContained(tempPathList))
            VertexDisjointContainer.add(tempPathList);
        }
        
    }
    public boolean isAlreadyContained(Vector<Vector<Vertex>> c){

        for(Vector<Vector<Vertex>> d:VertexDisjointContainer){
                if(d.containsAll(c))
                    return true;
        }
        return false;
    }
    public boolean areDisjointPaths(Vector<Vertex> path1, Vector<Vertex> path2) {

        List<Vertex> setA = new ArrayList<Vertex>();
        List<Vertex> setB = new ArrayList<Vertex>();

        setA = path1.subList(1, path1.size() - 1);
        setB = path2.subList(1, path2.size() - 1);
        return Collections.disjoint(setA, setB);

    }

    public void generatePaths() {
        pathList = new Vector<Vector<Vertex>>();
        Vector<Vertex> visitedNodes = new Vector<Vertex>();

        //  System.out.println("Vertex-Disjoint Paths for " + vertex1.name + "-" + vertex2.name);

        pathList.removeAllElements();
        visitedNodes.add(vertex1);
        recursePaths(vertex1, visitedNodes);

    }

    public void recursePaths(Vertex v, Vector<Vertex> visitedNodes) {
        if (visitedNodes.contains(vertex2)) {
            Vector<Vertex> Path = new Vector<Vertex>();
            Path.setSize(visitedNodes.size());
            Collections.copy(Path, visitedNodes);
            pathList.add(Path);
            for (Vertex a : Path) {
                System.out.print("-" + a.name);
            }
            System.out.println();
        } else {
            for (Vertex x : v.connectedVertices) {
                if (!visitedNodes.contains(x)) {
                    int origSize = visitedNodes.size();
                    visitedNodes.add(x);
                    recursePaths(x, visitedNodes);
                    visitedNodes.setSize(origSize);
                }
            }
        }

    }
    // public void

    public class Paths {

        public Vector<Vertex> Path = new Vector<Vertex>();
    }
}
