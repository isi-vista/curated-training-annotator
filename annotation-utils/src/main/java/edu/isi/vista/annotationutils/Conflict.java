package edu.isi.vista.annotationutils;

import java.util.ArrayList;
import java.util.List;

public class Conflict {

    private List<Attack> attacks;

    private List<Demonstrate> demonstrates;

    public Conflict(List<Attack> attacks, List<Demonstrate> demonstrates) {
        this.attacks = new ArrayList<>(attacks);
        this.demonstrates = new ArrayList<>(demonstrates);
    }

    public List<Attack> getAttacks() {
        return new ArrayList<>(attacks);
    }

    public List<Demonstrate> getDemonstrates() {
        return new ArrayList<>(demonstrates);
    }
}
